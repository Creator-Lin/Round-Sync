package dav

import (
	"bytes"
	"context"
	"crypto/subtle"
	"encoding/xml"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/url"
	"path"
	"strconv"
	"strings"
	"time"

	"quarkdav/internal/drive"
	"quarkdav/internal/remotefs"
	"quarkdav/internal/util"
)

type Handler struct {
	Prefix   string
	FS       *remotefs.FS
	Locks    *LockSystem
	Username string
	Password string
	NoAuth   bool
	Logger   *log.Logger
}

type loggingResponseWriter struct {
	http.ResponseWriter
	status int
	bytes  int64
}

func (w *loggingResponseWriter) WriteHeader(status int) {
	if w.status != 0 {
		return
	}
	w.status = status
	w.ResponseWriter.WriteHeader(status)
}

func (w *loggingResponseWriter) Write(p []byte) (int, error) {
	if w.status == 0 {
		w.WriteHeader(http.StatusOK)
	}
	n, err := w.ResponseWriter.Write(p)
	w.bytes += int64(n)
	return n, err
}

func (w *loggingResponseWriter) Flush() {
	if f, ok := w.ResponseWriter.(http.Flusher); ok {
		f.Flush()
	}
}

func (w *loggingResponseWriter) Unwrap() http.ResponseWriter {
	return w.ResponseWriter
}

func (h *Handler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	start := time.Now()
	lw := &loggingResponseWriter{ResponseWriter: w}
	displayPath := util.DisplayPath(r.URL.Path)
	if h.Logger != nil {
		h.Logger.Printf("request method=%s path=%q req_bytes=%s remote=%q user_agent=%q", r.Method, displayPath, util.FormatBytes(r.ContentLength), r.RemoteAddr, util.EscapeLogString(r.UserAgent()))
	}
	var status int
	var err error
	defer func() {
		if h.Logger == nil {
			return
		}
		loggedStatus := lw.status
		if loggedStatus == 0 {
			if status != 0 {
				loggedStatus = status
			} else {
				loggedStatus = http.StatusOK
			}
		}
		duration := time.Since(start).Round(time.Millisecond)
		if err != nil {
			h.Logger.Printf("access method=%s path=%q status=%d bytes=%s req_bytes=%s duration=%s err=%q", r.Method, displayPath, loggedStatus, util.FormatBytes(lw.bytes), util.FormatBytes(r.ContentLength), duration, err.Error())
			return
		}
		h.Logger.Printf("access method=%s path=%q status=%d bytes=%s req_bytes=%s duration=%s", r.Method, displayPath, loggedStatus, util.FormatBytes(lw.bytes), util.FormatBytes(r.ContentLength), duration)
	}()
	if h.Locks == nil {
		h.Locks = NewLockSystem()
	}
	if !h.NoAuth && !h.checkAuth(lw, r) {
		return
	}
	reqPath, status, err := h.stripPrefix(r.URL.Path)
	if err != nil {
		h.writeError(lw, r, status, err)
		return
	}
	displayPath = util.DisplayPath(reqPath)
	switch r.Method {
	case "OPTIONS":
		status, err = h.handleOptions(lw, r, reqPath)
	case "GET", "HEAD", "POST":
		status, err = h.handleGetHeadPost(lw, r, reqPath)
	case "PROPFIND":
		status, err = h.handlePropfind(lw, r, reqPath)
	case "PROPPATCH":
		status, err = h.handleProppatch(lw, r, reqPath)
	case "PUT":
		status, err = h.handlePut(lw, r, reqPath)
	case "MKCOL":
		status, err = h.handleMkcol(lw, r, reqPath)
	case "DELETE":
		status, err = h.handleDelete(lw, r, reqPath)
	case "MOVE", "COPY":
		status, err = h.handleCopyMove(lw, r, reqPath)
	case "LOCK":
		status, err = h.handleLock(lw, r, reqPath)
	case "UNLOCK":
		status, err = h.handleUnlock(lw, r, reqPath)
	default:
		status, err = http.StatusBadRequest, fmt.Errorf("unsupported method")
	}
	if status != 0 {
		h.writeError(lw, r, status, err)
	} else if err != nil && h.Logger != nil {
		h.Logger.Printf("error method=%s path=%q err=%q", r.Method, displayPath, err.Error())
	}
}

func (h *Handler) checkAuth(w http.ResponseWriter, r *http.Request) bool {
	u, p, ok := r.BasicAuth()
	if !ok || subtle.ConstantTimeCompare([]byte(u), []byte(h.Username)) != 1 || subtle.ConstantTimeCompare([]byte(p), []byte(h.Password)) != 1 {
		w.Header().Set("WWW-Authenticate", `Basic realm="QuarkDav"`)
		w.WriteHeader(http.StatusUnauthorized)
		_, _ = w.Write([]byte("Unauthorized"))
		return false
	}
	return true
}

func (h *Handler) stripPrefix(raw string) (string, int, error) {
	prefix := h.Prefix
	if prefix == "" {
		prefix = "/"
	}
	if prefix != "/" {
		if raw != prefix && !strings.HasPrefix(raw, strings.TrimRight(prefix, "/")+"/") {
			return raw, http.StatusNotFound, fmt.Errorf("prefix mismatch")
		}
		raw = strings.TrimPrefix(raw, strings.TrimRight(prefix, "/"))
	}
	if raw == "" {
		raw = "/"
	}
	p, err := url.PathUnescape(raw)
	if err != nil {
		return raw, http.StatusBadRequest, err
	}
	return remotefs.Clean(p), 0, nil
}

func (h *Handler) hrefFor(reqPath string, isDir bool) string {
	prefix := strings.TrimRight(h.Prefix, "/")
	if prefix == "" {
		prefix = "/"
	}
	href := path.Join(prefix, reqPath)
	if !strings.HasPrefix(href, "/") {
		href = "/" + href
	}
	if isDir && !strings.HasSuffix(href, "/") {
		href += "/"
	}
	return (&url.URL{Path: href}).EscapedPath()
}

func (h *Handler) writeError(w http.ResponseWriter, r *http.Request, status int, err error) {
	if status == 0 {
		return
	}
	if err != nil && h.Logger != nil {
		h.Logger.Printf("error method=%s path=%q status=%d err=%q", r.Method, util.DisplayPath(r.URL.Path), status, err.Error())
	}
	w.WriteHeader(status)
	if status != http.StatusNoContent {
		text := http.StatusText(status)
		if text == "" && status == statusLocked {
			text = "Locked"
		}
		_, _ = w.Write([]byte(text))
	}
}

func (h *Handler) handleOptions(w http.ResponseWriter, r *http.Request, reqPath string) (int, error) {
	allow := "OPTIONS, LOCK, PUT, MKCOL"
	if fi, err := h.FS.Stat(r.Context(), reqPath); err == nil {
		if fi.IsDir {
			allow = "OPTIONS, LOCK, DELETE, PROPPATCH, COPY, MOVE, UNLOCK, PROPFIND"
			if h.FS.Driver.Writable() {
				allow += ", PUT, MKCOL"
			}
		} else {
			allow = "OPTIONS, LOCK, GET, HEAD, POST, DELETE, PROPPATCH, COPY, MOVE, UNLOCK, PROPFIND, PUT"
		}
	}
	w.Header().Set("Allow", allow)
	w.Header().Set("DAV", "1, 2")
	w.Header().Set("MS-Author-Via", "DAV")
	return 0, nil
}

func (h *Handler) handleGetHeadPost(w http.ResponseWriter, r *http.Request, reqPath string) (int, error) {
	fi, err := h.FS.Stat(r.Context(), reqPath)
	if err != nil {
		return mapErr(err), err
	}
	if fi.IsDir {
		if r.Method == http.MethodHead {
			w.Header().Set("Content-Type", "httpd/unix-directory")
			w.Header().Set("Content-Length", "0")
			return http.StatusOK, nil
		}
		return http.StatusMethodNotAllowed, nil
	}
	if r.Method == http.MethodHead {
		w.Header().Set("Content-Length", strconv.FormatInt(fi.Size, 10))
		w.Header().Set("Last-Modified", fi.ModTime.UTC().Format(http.TimeFormat))
		w.Header().Set("Etag", fi.ETag())
		w.Header().Set("Accept-Ranges", "bytes")
		w.Header().Set("Content-Type", util.MIMEByName(fi.Name))
		return http.StatusOK, nil
	}
	link, _, err := h.FS.Link(r.Context(), reqPath, r.Header)
	if err != nil {
		return mapErr(err), err
	}
	outReq, err := http.NewRequestWithContext(r.Context(), http.MethodGet, link.URL, nil)
	if err != nil {
		return http.StatusInternalServerError, err
	}
	for k, vals := range link.Header {
		for _, v := range vals {
			outReq.Header.Add(k, v)
		}
	}
	for _, k := range []string{"Range", "If-Range", "User-Agent"} {
		if v := r.Header.Values(k); len(v) > 0 && outReq.Header.Get(k) == "" {
			for _, x := range v {
				outReq.Header.Add(k, x)
			}
		}
	}
	resp, err := http.DefaultClient.Do(outReq)
	if err != nil {
		return http.StatusInternalServerError, err
	}
	defer resp.Body.Close()
	copyResponseHeaders(w.Header(), resp.Header)
	w.Header().Set("Etag", fi.ETag())
	if w.Header().Get("Content-Type") == "" {
		w.Header().Set("Content-Type", util.MIMEByName(fi.Name))
	}
	if w.Header().Get("Accept-Ranges") == "" {
		w.Header().Set("Accept-Ranges", "bytes")
	}
	w.WriteHeader(resp.StatusCode)
	_, err = io.Copy(w, resp.Body)
	return 0, err
}

func copyResponseHeaders(dst, src http.Header) {
	keys := []string{"Content-Type", "Content-Length", "Content-Range", "Accept-Ranges", "Last-Modified", "Cache-Control", "Expires"}
	for _, k := range keys {
		if vals := src.Values(k); len(vals) > 0 {
			dst.Del(k)
			for _, v := range vals {
				dst.Add(k, v)
			}
		}
	}
}

func (h *Handler) handlePropfind(w http.ResponseWriter, r *http.Request, reqPath string) (int, error) {
	fi, err := h.FS.Stat(r.Context(), reqPath)
	if err != nil {
		return mapErr(err), err
	}
	depth := -1
	if hdr := r.Header.Get("Depth"); hdr != "" {
		var ok bool
		depth, ok = parseDepth(hdr)
		if !ok {
			return http.StatusBadRequest, fmt.Errorf("invalid depth")
		}
	}
	raw, err := io.ReadAll(r.Body)
	if err != nil {
		return http.StatusBadRequest, err
	}
	pf, err := readPropfind(raw)
	if err != nil {
		return http.StatusBadRequest, err
	}
	var b bytes.Buffer
	b.WriteString(xml.Header)
	b.WriteString(`<D:multistatus xmlns:D="DAV:" xmlns:oc="http://owncloud.org/ns">`)
	err = h.walk(r.Context(), reqPath, fi, depth, func(p string, e *drive.Entry) error {
		writePropfindResponse(r.Context(), h, &b, h.hrefFor(p, e.IsDir), e, pf, r.Header.Get("User-Agent"))
		return nil
	})
	if err != nil {
		return http.StatusInternalServerError, err
	}
	b.WriteString(`</D:multistatus>`)
	w.Header().Set("Content-Type", "application/xml; charset=utf-8")
	w.WriteHeader(207)
	_, err = w.Write(b.Bytes())
	return 0, err
}

func (h *Handler) walk(ctx context.Context, reqPath string, e *drive.Entry, depth int, fn func(string, *drive.Entry) error) error {
	if err := fn(reqPath, e); err != nil {
		return err
	}
	if !e.IsDir || depth == 0 {
		return nil
	}
	nextDepth := depth
	if depth > 0 {
		nextDepth = depth - 1
	}
	children, err := h.FS.ListPath(ctx, reqPath)
	if err != nil {
		return err
	}
	for _, child := range children {
		if err := h.walk(ctx, path.Join(reqPath, child.Name), child, nextDepth, fn); err != nil {
			return err
		}
	}
	return nil
}

func (h *Handler) handleProppatch(w http.ResponseWriter, r *http.Request, reqPath string) (int, error) {
	if !h.Locks.Check([]string{reqPath}, r.Header.Get("If")) {
		return http.StatusPreconditionFailed, errors.New("locked")
	}
	if _, err := h.FS.Stat(r.Context(), reqPath); err != nil {
		return mapErr(err), err
	}
	raw, _ := io.ReadAll(r.Body)
	propPatchForbidden(w, h.hrefFor(reqPath, false), raw)
	return 0, nil
}

func (h *Handler) handlePut(w http.ResponseWriter, r *http.Request, reqPath string) (int, error) {
	defer func() { _ = r.Body.Close() }()
	if reqPath == "/" {
		return http.StatusMethodNotAllowed, nil
	}
	if !h.FS.Driver.Writable() {
		return http.StatusMethodNotAllowed, drive.ErrUnsupported
	}
	if !h.Locks.Check([]string{reqPath}, r.Header.Get("If")) {
		return http.StatusPreconditionFailed, errors.New("locked")
	}
	size := r.ContentLength
	if size < 0 && r.Header.Get("X-File-Size") != "" {
		if parsed, err := strconv.ParseInt(r.Header.Get("X-File-Size"), 10, 64); err == nil {
			size = parsed
		}
	}
	mod := util.HeaderUnixTime(r, "X-OC-Mtime", "")
	ctime := util.HeaderUnixTime(r, "X-OC-Ctime", "X-OC-Mtime")
	obj, err := h.FS.Put(r.Context(), reqPath, r.Body, size, r.Header.Get("Content-Type"), mod, ctime)
	if err != nil {
		return mapErr(err), err
	}
	if obj != nil {
		w.Header().Set("Etag", obj.ETag())
	}
	return http.StatusCreated, nil
}

func (h *Handler) handleMkcol(w http.ResponseWriter, r *http.Request, reqPath string) (int, error) {
	if !h.FS.Driver.Writable() {
		return http.StatusMethodNotAllowed, drive.ErrUnsupported
	}
	if !h.Locks.Check([]string{reqPath}, r.Header.Get("If")) {
		return http.StatusPreconditionFailed, errors.New("locked")
	}
	if r.ContentLength > 0 {
		return http.StatusUnsupportedMediaType, nil
	}
	if _, err := h.FS.Stat(r.Context(), reqPath); err == nil {
		return http.StatusMethodNotAllowed, drive.ErrAlreadyExists
	}
	if _, _, err := h.FS.ResolveParent(r.Context(), reqPath); err != nil {
		return http.StatusConflict, err
	}
	if err := h.FS.MakeDir(r.Context(), reqPath); err != nil {
		return mapErr(err), err
	}
	return http.StatusCreated, nil
}

func (h *Handler) handleDelete(w http.ResponseWriter, r *http.Request, reqPath string) (int, error) {
	if !h.FS.Driver.Writable() {
		return http.StatusMethodNotAllowed, drive.ErrUnsupported
	}
	if !h.Locks.Check([]string{reqPath}, r.Header.Get("If")) {
		return http.StatusPreconditionFailed, errors.New("locked")
	}
	if err := h.FS.Remove(r.Context(), reqPath); err != nil {
		return mapErr(err), err
	}
	return http.StatusNoContent, nil
}

func (h *Handler) handleCopyMove(w http.ResponseWriter, r *http.Request, src string) (int, error) {
	if !h.FS.Driver.Writable() {
		return http.StatusMethodNotAllowed, drive.ErrUnsupported
	}
	hdr := r.Header.Get("Destination")
	if hdr == "" {
		return http.StatusBadRequest, fmt.Errorf("missing destination")
	}
	u, err := url.Parse(hdr)
	if err != nil {
		return http.StatusBadRequest, err
	}
	if u.Host != "" && u.Host != r.Host {
		return http.StatusBadGateway, fmt.Errorf("invalid destination host")
	}
	dst, status, err := h.stripPrefix(u.Path)
	if err != nil {
		return status, err
	}
	if dst == "/" {
		return http.StatusBadGateway, fmt.Errorf("invalid destination")
	}
	if dst == src {
		return http.StatusForbidden, fmt.Errorf("destination equals source")
	}
	if !h.Locks.Check([]string{src, dst}, r.Header.Get("If")) {
		return http.StatusPreconditionFailed, errors.New("locked")
	}
	overwrite := r.Header.Get("Overwrite") != "F"
	if r.Method == "COPY" {
		depth := -1
		if hdr := r.Header.Get("Depth"); hdr != "" {
			var ok bool
			depth, ok = parseDepth(hdr)
			if !ok || (depth != 0 && depth != -1) {
				return http.StatusBadRequest, fmt.Errorf("invalid depth")
			}
		}
		if err := h.FS.Copy(r.Context(), src, dst, overwrite, depth); err != nil {
			return mapErr(err), err
		}
		return http.StatusCreated, nil
	}
	if hdr := r.Header.Get("Depth"); hdr != "" {
		depth, ok := parseDepth(hdr)
		if !ok || depth != -1 {
			return http.StatusBadRequest, fmt.Errorf("invalid depth")
		}
	}
	if err := h.FS.Move(r.Context(), src, dst, overwrite); err != nil {
		return mapErr(err), err
	}
	return http.StatusCreated, nil
}

func (h *Handler) handleLock(w http.ResponseWriter, r *http.Request, reqPath string) (int, error) {
	depth := -1
	if hdr := r.Header.Get("Depth"); hdr != "" {
		var ok bool
		depth, ok = parseDepth(hdr)
		if !ok || (depth != 0 && depth != -1) {
			return http.StatusBadRequest, fmt.Errorf("invalid depth")
		}
	}
	timeout := parseTimeout(r.Header.Get("Timeout"))
	raw, _ := io.ReadAll(r.Body)
	if len(bytes.TrimSpace(raw)) == 0 {
		tokens := parseLockTokens(r.Header.Get("If"))
		for t := range tokens {
			if li, ok := h.Locks.Refresh(t, timeout); ok {
				writeLockResponse(w, li)
				return 0, nil
			}
		}
		return http.StatusBadRequest, fmt.Errorf("missing lock info")
	}
	if !h.Locks.Check([]string{reqPath}, r.Header.Get("If")) {
		return statusLocked, errors.New("locked")
	}
	ownerXML := extractOwnerXML(raw)
	token := h.Locks.Create(reqPath, ownerXML, depth == 0, timeout)
	w.Header().Set("Lock-Token", "<"+token+">")
	li, _ := h.Locks.Refresh(token, timeout)
	w.WriteHeader(http.StatusOK)
	writeLockInfo(w, li)
	return 0, nil
}

func (h *Handler) handleUnlock(w http.ResponseWriter, r *http.Request, reqPath string) (int, error) {
	token := strings.Trim(r.Header.Get("Lock-Token"), "<>")
	if token == "" {
		return http.StatusBadRequest, fmt.Errorf("invalid lock token")
	}
	if !h.Locks.Unlock(token) {
		return http.StatusConflict, fmt.Errorf("no such lock")
	}
	return http.StatusNoContent, nil
}

func parseDepth(s string) (int, bool) {
	switch strings.ToLower(strings.TrimSpace(s)) {
	case "0":
		return 0, true
	case "1":
		return 1, true
	case "infinity":
		return -1, true
	default:
		return 0, false
	}
}

func parseTimeout(s string) time.Duration {
	s = strings.TrimSpace(s)
	if strings.HasPrefix(strings.ToLower(s), "second-") {
		if n, err := strconv.Atoi(s[7:]); err == nil && n > 0 {
			return time.Duration(n) * time.Second
		}
	}
	return time.Hour
}

func extractOwnerXML(raw []byte) string {
	type lockInfoXML struct {
		Owner struct {
			Inner string `xml:",innerxml"`
		} `xml:"DAV: owner"`
	}
	var li lockInfoXML
	if err := xml.Unmarshal(raw, &li); err == nil {
		return li.Owner.Inner
	}
	return ""
}

func writeLockResponse(w http.ResponseWriter, li lockInfo) {
	w.Header().Set("Content-Type", "application/xml; charset=utf-8")
	writeLockInfo(w, li)
}

func writeLockInfo(w http.ResponseWriter, li lockInfo) {
	_, _ = w.Write([]byte(xml.Header))
	_, _ = w.Write([]byte(`<D:prop xmlns:D="DAV:"><D:lockdiscovery>`))
	_, _ = w.Write([]byte(`<D:activelock><D:locktype><D:write/></D:locktype><D:lockscope><D:exclusive/></D:lockscope>`))
	depth := "infinity"
	if li.ZeroDepth {
		depth = "0"
	}
	_, _ = w.Write([]byte(`<D:depth>` + depth + `</D:depth><D:timeout>Second-3600</D:timeout>`))
	if li.OwnerXML != "" {
		_, _ = w.Write([]byte(`<D:owner>` + li.OwnerXML + `</D:owner>`))
	}
	_, _ = w.Write([]byte(`<D:locktoken><D:href>` + li.Token + `</D:href></D:locktoken></D:activelock>`))
	_, _ = w.Write([]byte(`</D:lockdiscovery></D:prop>`))
}

func mapErr(err error) int {
	switch {
	case err == nil:
		return 0
	case errors.Is(err, drive.ErrNotFound):
		return http.StatusNotFound
	case errors.Is(err, drive.ErrAlreadyExists):
		return http.StatusPreconditionFailed
	case errors.Is(err, drive.ErrUnsupported):
		return http.StatusMethodNotAllowed
	case errors.Is(err, drive.ErrNotDir):
		return http.StatusConflict
	case errors.Is(err, drive.ErrIsDir):
		return http.StatusMethodNotAllowed
	case errors.Is(err, drive.ErrLengthRequired):
		return http.StatusLengthRequired
	default:
		return http.StatusInternalServerError
	}
}

var _ = context.Background
