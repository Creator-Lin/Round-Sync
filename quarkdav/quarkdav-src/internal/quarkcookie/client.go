package quarkcookie

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"html"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	"quarkdav/internal/app"
	"quarkdav/internal/drive"
	"quarkdav/internal/util"
)

const quarkUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) quark-cloud-drive/2.5.20 Chrome/100.0.4896.160 Electron/18.3.5.4-b478491100 Safari/537.36 Channel/pckk_other_ch"
const ucUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) uc-cloud-drive/2.5.20 Chrome/100.0.4896.160 Electron/18.3.5.4-b478491100 Safari/537.36 Channel/pckk_other_ch"

type Client struct {
	cfg     *app.CookieConfig
	save    func() error
	http    *http.Client
	ua      string
	referer string
	api     string
	pr      string
}

func New(cfg *app.CookieConfig, save func() error) *Client {
	c := &Client{cfg: cfg, save: save, http: &http.Client{Timeout: 0}}
	brand := strings.ToLower(strings.TrimSpace(cfg.Brand))
	if brand == "uc" {
		c.ua = ucUA
		c.referer = "https://drive.uc.cn"
		c.api = "https://pc-api.uc.cn/1/clouddrive"
		c.pr = "UCBrowser"
	} else {
		c.ua = quarkUA
		c.referer = "https://pan.quark.cn"
		c.api = "https://drive.quark.cn/1/clouddrive"
		c.pr = "ucpro"
	}
	return c
}

func (c *Client) Name() string   { return "QuarkCookie" }
func (c *Client) RootID() string { return drive.CleanRootID(c.cfg.RootID) }
func (c *Client) Writable() bool { return true }

func (c *Client) Init(ctx context.Context) error {
	_, err := c.request(ctx, "/config", http.MethodGet, nil, nil, nil)
	return err
}

func (c *Client) List(ctx context.Context, parentID string) ([]*drive.Entry, error) {
	out := make([]*drive.Entry, 0)
	page := 1
	size := 100
	query := map[string]string{
		"pdir_fid":             parentID,
		"_size":                strconv.Itoa(size),
		"_fetch_total":         "1",
		"fetch_all_file":       "1",
		"fetch_risk_file_name": "1",
	}
	if c.cfg.OrderBy != "" && c.cfg.OrderBy != "none" {
		query["_sort"] = "file_type:asc," + c.cfg.OrderBy + ":" + c.cfg.OrderDirection
	}
	for {
		query["_page"] = strconv.Itoa(page)
		var sr sortResp
		if _, err := c.request(ctx, "/file/sort", http.MethodGet, query, nil, &sr); err != nil {
			return nil, err
		}
		for _, f := range sr.Data.List {
			f.FileName = html.UnescapeString(f.FileName)
			isDir := !f.File
			if c.cfg.OnlyListVideoFile && !isDir && f.Category != 1 {
				continue
			}
			out = append(out, &drive.Entry{
				ID:         f.Fid,
				Name:       f.FileName,
				Size:       f.Size,
				Category:   f.Category,
				IsDir:      isDir,
				ModTime:    unixMilli(f.UpdatedAt, f.LUpdatedAt),
				CreateTime: unixMilli(f.CreatedAt, f.LCreatedAt),
			})
		}
		if page*size >= sr.Metadata.Total || len(sr.Data.List) == 0 {
			break
		}
		page++
	}
	return out, nil
}

func (c *Client) Link(ctx context.Context, file *drive.Entry, requestHeader http.Header) (*drive.Link, error) {
	if c.cfg.UseTranscodingAddress && strings.ToLower(c.cfg.Brand) != "uc" && file.Category == 1 && file.Size > 0 {
		if link, err := c.getTranscodingLink(ctx, file); err == nil {
			return link, nil
		}
	}
	return c.getDownloadLink(ctx, file)
}

func (c *Client) MakeDir(ctx context.Context, parentID, name string) error {
	data := map[string]any{
		"dir_init_lock": false,
		"dir_path":      "",
		"file_name":     name,
		"pdir_fid":      parentID,
	}
	_, err := c.request(ctx, "/file", http.MethodPost, nil, data, nil)
	if err == nil || err.Error() == "file is doloading[同名冲突]" {
		time.Sleep(time.Second)
	}
	if err != nil && err.Error() == "file is doloading[同名冲突]" {
		return drive.ErrAlreadyExists
	}
	return err
}

func (c *Client) Move(ctx context.Context, src *drive.Entry, dstParent *drive.Entry) error {
	data := map[string]any{
		"action_type":  1,
		"exclude_fids": []string{},
		"filelist":     []string{src.ID},
		"to_pdir_fid":  dstParent.ID,
	}
	_, err := c.request(ctx, "/file/move", http.MethodPost, nil, data, nil)
	return err
}

func (c *Client) Rename(ctx context.Context, src *drive.Entry, newName string) error {
	data := map[string]any{"fid": src.ID, "file_name": newName}
	_, err := c.request(ctx, "/file/rename", http.MethodPost, nil, data, nil)
	return err
}

func (c *Client) Remove(ctx context.Context, obj *drive.Entry) error {
	data := map[string]any{
		"action_type":  1,
		"exclude_fids": []string{},
		"filelist":     []string{obj.ID},
	}
	_, err := c.request(ctx, "/file/delete", http.MethodPost, nil, data, nil)
	return err
}

func (c *Client) Details(ctx context.Context) (*drive.Details, error) {
	var mr memberResp
	q := map[string]string{"fetch_subscribe": "false", "_ch": "home", "fetch_identity": "false"}
	if _, err := c.request(ctx, "/member", http.MethodGet, q, nil, &mr); err != nil {
		return nil, err
	}
	return &drive.Details{TotalSpace: mr.Data.TotalCapacity, UsedSpace: mr.Data.UseCapacity}, nil
}

func (c *Client) request(ctx context.Context, pathname, method string, query map[string]string, body any, out any) ([]byte, error) {
	u, err := url.Parse(c.api + pathname)
	if err != nil {
		return nil, err
	}
	q := u.Query()
	q.Set("pr", c.pr)
	q.Set("fr", "pc")
	for k, v := range query {
		q.Set(k, v)
	}
	u.RawQuery = q.Encode()

	var rbody io.Reader
	if body != nil {
		b, err := json.Marshal(body)
		if err != nil {
			return nil, err
		}
		rbody = bytes.NewReader(b)
	}
	req, err := http.NewRequestWithContext(ctx, method, u.String(), rbody)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Cookie", c.cfg.Cookie)
	req.Header.Set("Accept", "application/json, text/plain, */*")
	req.Header.Set("Referer", c.referer)
	req.Header.Set("User-Agent", c.ua)
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	resp, err := c.http.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	raw, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, err
	}
	if ck := util.FirstCookie(resp.Cookies(), "__puus"); ck != nil {
		c.cfg.Cookie = util.SetCookieValue(c.cfg.Cookie, "__puus", ck.Value)
		if c.save != nil {
			_ = c.save()
		}
	}
	if c.cfg.UseTranscodingAddress && strings.ToLower(c.cfg.Brand) != "uc" {
		if ck := util.FirstCookie(resp.Cookies(), "__pus"); ck != nil {
			c.cfg.Cookie = util.SetCookieValue(c.cfg.Cookie, "__pus", ck.Value)
			if c.save != nil {
				_ = c.save()
			}
		}
	}
	var st respStatus
	_ = json.Unmarshal(raw, &st)
	if resp.StatusCode >= 400 || st.Status >= 400 || st.Code != 0 {
		msg := st.Message
		if msg == "" {
			msg = strings.TrimSpace(string(raw))
		}
		if msg == "" {
			msg = resp.Status
		}
		return nil, errors.New(msg)
	}
	if out != nil && len(raw) > 0 {
		if err := json.Unmarshal(raw, out); err != nil {
			return nil, err
		}
	}
	return raw, nil
}

func (c *Client) getDownloadLink(ctx context.Context, file *drive.Entry) (*drive.Link, error) {
	data := map[string]any{"fids": []string{file.ID}}
	var dr downResp
	if _, err := c.request(ctx, "/file/download", http.MethodPost, nil, data, &dr); err != nil {
		return nil, err
	}
	if len(dr.Data) == 0 || dr.Data[0].DownloadURL == "" {
		return nil, errors.New("empty download url")
	}
	return &drive.Link{
		URL: dr.Data[0].DownloadURL,
		Header: http.Header{
			"Cookie":     []string{c.cfg.Cookie},
			"Referer":    []string{c.referer},
			"User-Agent": []string{c.ua},
		},
		ContentLength: file.Size,
	}, nil
}

func (c *Client) getTranscodingLink(ctx context.Context, file *drive.Entry) (*drive.Link, error) {
	data := map[string]any{
		"fid":         file.ID,
		"resolutions": "low,normal,high,super,2k,4k",
		"supports":    "fmp4_av,m3u8,dolby_vision",
	}
	var tr transcodingResp
	if _, err := c.request(ctx, "/file/v2/play/project", http.MethodPost, nil, data, &tr); err != nil {
		return nil, err
	}
	for _, info := range tr.Data.VideoList {
		if info.VideoInfo.URL != "" {
			return &drive.Link{URL: info.VideoInfo.URL, ContentLength: info.VideoInfo.Size}, nil
		}
	}
	return nil, errors.New("no transcoding link found")
}

func unixMilli(primary int64, fallback int64) time.Time {
	v := primary
	if v == 0 {
		v = fallback
	}
	if v == 0 {
		return time.Now()
	}
	return time.UnixMilli(v)
}

func trimUploadHost(uploadURL string) string {
	uploadURL = strings.TrimPrefix(uploadURL, "https://")
	uploadURL = strings.TrimPrefix(uploadURL, "http://")
	return uploadURL
}

func (c *Client) ossURL(pre upPreResp) string {
	return fmt.Sprintf("https://%s.%s/%s", pre.Data.Bucket, trimUploadHost(pre.Data.UploadURL), pre.Data.ObjKey)
}

func callbackBase64(pre upPreResp) (string, error) {
	b, err := json.Marshal(pre.Data.Callback)
	if err != nil {
		return "", err
	}
	return base64.StdEncoding.EncodeToString(b), nil
}
