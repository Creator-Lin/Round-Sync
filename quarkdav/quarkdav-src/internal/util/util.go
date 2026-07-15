package util

import (
	"context"
	"crypto/md5"
	"crypto/rand"
	"crypto/sha1"
	"encoding/hex"
	"fmt"
	"hash"
	"io"
	"mime"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

const MB int64 = 1024 * 1024

func NewUUID() string {
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		n := time.Now().UnixNano()
		return fmt.Sprintf("%08x-%04x-%04x-%04x-%012x", uint32(n), uint16(n>>32), uint16(n>>48), uint16(n), uint64(n))
	}
	b[6] = (b[6] & 0x0f) | 0x40
	b[8] = (b[8] & 0x3f) | 0x80
	return fmt.Sprintf("%08x-%04x-%04x-%04x-%012x",
		b[0:4], b[4:6], b[6:8], b[8:10], b[10:16])
}

func HashFile(path string) (md5Hex, sha1Hex string, size int64, err error) {
	f, err := os.Open(path)
	if err != nil {
		return "", "", 0, err
	}
	defer f.Close()
	st, err := f.Stat()
	if err != nil {
		return "", "", 0, err
	}
	m := md5.New()
	s := sha1.New()
	if _, err := io.Copy(io.MultiWriter(m, s), f); err != nil {
		return "", "", 0, err
	}
	return hex.EncodeToString(m.Sum(nil)), hex.EncodeToString(s.Sum(nil)), st.Size(), nil
}

func HashReaderToTemp(dir string, r io.Reader, progress func(written int64)) (path string, md5Hex string, sha1Hex string, size int64, err error) {
	f, err := os.CreateTemp(dir, "quarkdav-upload-*")
	if err != nil {
		return "", "", "", 0, err
	}
	defer func() {
		if cerr := f.Close(); err == nil && cerr != nil {
			err = cerr
		}
		if err != nil && path != "" {
			_ = os.Remove(path)
		}
	}()
	path = f.Name()
	m := md5.New()
	s := sha1.New()
	mw := io.MultiWriter(f, m, s)
	buf := make([]byte, 1024*1024)
	for {
		n, er := r.Read(buf)
		if n > 0 {
			if _, ew := mw.Write(buf[:n]); ew != nil {
				err = ew
				return
			}
			size += int64(n)
			if progress != nil {
				progress(size)
			}
		}
		if er == io.EOF {
			break
		}
		if er != nil {
			err = er
			return
		}
	}
	md5Hex = hex.EncodeToString(m.Sum(nil))
	sha1Hex = hex.EncodeToString(s.Sum(nil))
	return
}

func MIMEByName(name string) string {
	ext := strings.ToLower(filepath.Ext(name))
	if ext != "" {
		if t := mime.TypeByExtension(ext); t != "" {
			return t
		}
	}
	return "application/octet-stream"
}

func CopyHeader(dst, src http.Header, keys ...string) {
	for _, k := range keys {
		if values, ok := src[k]; ok {
			for _, v := range values {
				dst.Add(k, v)
			}
		}
	}
}

func FirstCookie(cookies []*http.Cookie, name string) *http.Cookie {
	for _, c := range cookies {
		if c != nil && c.Name == name {
			return c
		}
	}
	return nil
}

func SetCookieValue(cookieLine, name, value string) string {
	parts := strings.Split(cookieLine, ";")
	found := false
	for i, p := range parts {
		p = strings.TrimSpace(p)
		if p == "" {
			continue
		}
		kv := strings.SplitN(p, "=", 2)
		if len(kv) == 2 && strings.TrimSpace(kv[0]) == name {
			parts[i] = " " + name + "=" + value
			found = true
		}
	}
	if !found {
		if strings.TrimSpace(cookieLine) == "" {
			return name + "=" + value
		}
		return strings.TrimSpace(cookieLine) + "; " + name + "=" + value
	}
	return strings.Trim(strings.Join(parts, ";"), " ;")
}

func HeaderUnixTime(r *http.Request, header, alternative string) time.Time {
	h := strings.TrimSpace(r.Header.Get(header))
	if h == "" && alternative != "" {
		h = strings.TrimSpace(r.Header.Get(alternative))
	}
	if h != "" {
		if sec, err := strconv.ParseInt(h, 10, 64); err == nil {
			return time.Unix(sec, 0)
		}
	}
	return time.Now()
}

func CheckContext(ctx context.Context) error {
	select {
	case <-ctx.Done():
		return ctx.Err()
	default:
		return nil
	}
}

func Retry(ctx context.Context, attempts int, fn func() error) error {
	var err error
	if attempts < 1 {
		attempts = 1
	}
	for i := 0; i < attempts; i++ {
		if err = CheckContext(ctx); err != nil {
			return err
		}
		err = fn()
		if err == nil {
			return nil
		}
		if i+1 < attempts {
			t := time.NewTimer(time.Duration(1+i) * time.Second)
			select {
			case <-ctx.Done():
				t.Stop()
				return ctx.Err()
			case <-t.C:
			}
		}
	}
	return err
}

func HexHash(h hash.Hash) string { return hex.EncodeToString(h.Sum(nil)) }
