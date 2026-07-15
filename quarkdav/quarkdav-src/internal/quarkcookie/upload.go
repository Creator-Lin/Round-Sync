package quarkcookie

import (
	"bytes"
	"context"
	"crypto/md5"
	"crypto/sha1"
	"encoding/base64"
	"encoding/hex"
	"encoding/xml"
	"fmt"
	"hash"
	"io"
	"net/http"
	"strconv"
	"strings"
	"time"

	"quarkdav/internal/drive"
	"quarkdav/internal/util"
)

func (c *Client) Put(ctx context.Context, parentID string, up *drive.Upload, progress drive.Progress) error {
	if progress == nil {
		progress = func(float64) {}
	}
	if (up.MD5 == "" || up.SHA1 == "") && up.Path != "" {
		md5Hex, sha1Hex, realSize, err := util.HashFile(up.Path)
		if err != nil {
			return fmt.Errorf("hash upload file: %w", err)
		}
		up.MD5 = md5Hex
		up.SHA1 = sha1Hex
		if up.Size < 0 {
			up.Size = realSize
		}
	}
	pre, err := c.upPre(ctx, up, parentID)
	if err != nil {
		return err
	}
	partSize := int64(pre.Metadata.PartSize)
	if partSize <= 0 {
		partSize = 10 * util.MB
	}
	total := up.Size
	uploadNums := int((total + partSize - 1) / partSize)
	if uploadNums == 0 {
		uploadNums = 1
	}
	etags := make([]string, 0, uploadNums)
	f, err := up.Open()
	if err != nil {
		return err
	}
	defer f.Close()
	for partIndex := 0; partIndex < uploadNums; partIndex++ {
		if err := util.CheckContext(ctx); err != nil {
			return err
		}
		offset := int64(partIndex) * partSize
		size := partSize
		if remain := total - offset; remain < size {
			size = remain
		}
		if size < 0 {
			size = 0
		}
		sr := io.NewSectionReader(f, offset, size)
		var etag string
		err = util.Retry(ctx, 3, func() error {
			if _, err := sr.Seek(0, io.SeekStart); err != nil {
				return err
			}
			e, err := c.upPart(ctx, pre, up.MIME, partIndex+1, size, sr)
			if err != nil {
				return err
			}
			etag = e
			return nil
		})
		if err != nil {
			return fmt.Errorf("upload part %d: %w", partIndex+1, err)
		}
		etags = append(etags, etag)
		if total > 0 {
			progress(95 * float64(offset+size) / float64(total))
		}
	}
	progress(97)
	finish, err := c.upHash(ctx, up.MD5, up.SHA1, pre.Data.TaskID)
	if err != nil {
		return fmt.Errorf("submit upload hash: %w", err)
	}
	if finish || pre.Data.Finish {
		progress(100)
		return nil
	}
	if err := c.upCommit(ctx, pre, etags); err != nil {
		return fmt.Errorf("commit multipart upload: %w", err)
	}
	if err := c.upFinish(ctx, pre); err != nil {
		return fmt.Errorf("finish upload task: %w", err)
	}
	progress(100)
	return nil
}

func (c *Client) PutStream(ctx context.Context, parentID string, up *drive.Upload, r io.Reader, progress drive.Progress) error {
	if progress == nil {
		progress = func(float64) {}
	}
	if up.Size < 0 {
		return drive.ErrLengthRequired
	}
	pre, err := c.upPre(ctx, up, parentID)
	if err != nil {
		return err
	}
	partSize := int64(pre.Metadata.PartSize)
	if partSize <= 0 {
		partSize = 10 * util.MB
	}
	total := up.Size
	uploadNums := int((total + partSize - 1) / partSize)
	if uploadNums == 0 {
		uploadNums = 1
	}
	etags := make([]string, 0, uploadNums)
	md5Hash := md5.New()
	sha1Hash := sha1.New()
	var sent int64
	for partIndex := 0; partIndex < uploadNums; partIndex++ {
		if err := util.CheckContext(ctx); err != nil {
			return err
		}
		offset := int64(partIndex) * partSize
		size := partSize
		if remain := total - offset; remain < size {
			size = remain
		}
		if size < 0 {
			size = 0
		}
		limited := &io.LimitedReader{R: r, N: size}
		body := io.Reader(limited)
		if size > 0 {
			body = &streamHashProgressReader{
				r:        limited,
				total:    total,
				sent:     &sent,
				progress: progress,
				md5Hash:  md5Hash,
				sha1Hash: sha1Hash,
			}
		}
		etag, err := c.upPart(ctx, pre, up.MIME, partIndex+1, size, body)
		if err != nil {
			return fmt.Errorf("upload part %d: %w", partIndex+1, err)
		}
		if limited.N != 0 {
			return fmt.Errorf("upload part %d: %w", partIndex+1, io.ErrUnexpectedEOF)
		}
		etags = append(etags, etag)
		if total > 0 {
			progress(95 * float64(offset+size) / float64(total))
		}
	}
	progress(97)
	md5Hex := hex.EncodeToString(md5Hash.Sum(nil))
	sha1Hex := hex.EncodeToString(sha1Hash.Sum(nil))
	up.MD5 = md5Hex
	up.SHA1 = sha1Hex
	finish, err := c.upHash(ctx, md5Hex, sha1Hex, pre.Data.TaskID)
	if err != nil {
		return fmt.Errorf("submit upload hash: %w", err)
	}
	if finish || pre.Data.Finish {
		progress(100)
		return nil
	}
	if err := c.upCommit(ctx, pre, etags); err != nil {
		return fmt.Errorf("commit multipart upload: %w", err)
	}
	if err := c.upFinish(ctx, pre); err != nil {
		return fmt.Errorf("finish upload task: %w", err)
	}
	progress(100)
	return nil
}

type streamHashProgressReader struct {
	r        io.Reader
	total    int64
	sent     *int64
	progress drive.Progress
	md5Hash  hash.Hash
	sha1Hash hash.Hash
}

func (r *streamHashProgressReader) Read(p []byte) (int, error) {
	n, err := r.r.Read(p)
	if n > 0 {
		chunk := p[:n]
		if r.md5Hash != nil {
			_, _ = r.md5Hash.Write(chunk)
		}
		if r.sha1Hash != nil {
			_, _ = r.sha1Hash.Write(chunk)
		}
		*r.sent += int64(n)
		if r.total > 0 && r.progress != nil {
			r.progress(95 * float64(*r.sent) / float64(r.total))
		}
	}
	return n, err
}

func (c *Client) upPre(ctx context.Context, file *drive.Upload, parentID string) (upPreResp, error) {
	now := time.Now()
	if !file.ModTime.IsZero() {
		now = file.ModTime
	}
	created := now
	if !file.CreateTime.IsZero() {
		created = file.CreateTime
	}
	data := map[string]any{
		"ccp_hash_update": true,
		"dir_name":        "",
		"file_name":       file.Name,
		"format_type":     file.MIME,
		"l_created_at":    created.UnixMilli(),
		"l_updated_at":    now.UnixMilli(),
		"pdir_fid":        parentID,
		"size":            file.Size,
	}
	var resp upPreResp
	_, err := c.request(ctx, "/file/upload/pre", http.MethodPost, nil, data, &resp)
	return resp, err
}

func (c *Client) upHash(ctx context.Context, md5Hex, sha1Hex, taskID string) (bool, error) {
	if md5Hex == "" {
		return false, fmt.Errorf("md5 is empty")
	}
	if sha1Hex == "" {
		return false, fmt.Errorf("sha1 is empty")
	}
	data := map[string]any{"md5": md5Hex, "sha1": sha1Hex, "task_id": taskID}
	var resp hashResp
	_, err := c.request(ctx, "/file/update/hash", http.MethodPost, nil, data, &resp)
	return resp.Data.Finish, err
}

func (c *Client) upPart(ctx context.Context, pre upPreResp, mimeType string, partNumber int, contentLength int64, r io.Reader) (string, error) {
	if mimeType == "" {
		mimeType = "application/octet-stream"
	}
	timeStr := time.Now().UTC().Format(http.TimeFormat)
	authMeta := fmt.Sprintf("PUT\n\n%s\n%s\nx-oss-date:%s\nx-oss-user-agent:aliyun-sdk-js/6.6.1 Chrome 98.0.4758.80 on Windows 10 64-bit\n/%s/%s?partNumber=%d&uploadId=%s",
		mimeType, timeStr, timeStr, pre.Data.Bucket, pre.Data.ObjKey, partNumber, pre.Data.UploadID)
	data := map[string]any{"auth_info": pre.Data.AuthInfo, "auth_meta": authMeta, "task_id": pre.Data.TaskID}
	var ar upAuthResp
	if _, err := c.request(ctx, "/file/upload/auth", http.MethodPost, nil, data, &ar); err != nil {
		return "", err
	}
	body := r
	if contentLength == 0 {
		body = http.NoBody
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPut, c.ossURL(pre), body)
	if err != nil {
		return "", err
	}
	req.ContentLength = contentLength
	req.Header.Set("Authorization", ar.Data.AuthKey)
	req.Header.Set("Content-Type", mimeType)
	req.Header.Set("Referer", "https://pan.quark.cn/")
	req.Header.Set("x-oss-date", timeStr)
	req.Header.Set("x-oss-user-agent", "aliyun-sdk-js/6.6.1 Chrome 98.0.4758.80 on Windows 10 64-bit")
	q := req.URL.Query()
	q.Set("partNumber", strconv.Itoa(partNumber))
	q.Set("uploadId", pre.Data.UploadID)
	req.URL.RawQuery = q.Encode()
	resp, err := c.http.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		b, _ := io.ReadAll(resp.Body)
		return "", fmt.Errorf("up status: %d, error: %s", resp.StatusCode, string(b))
	}
	return resp.Header.Get("Etag"), nil
}

type completeMultipartUpload struct {
	XMLName xml.Name       `xml:"CompleteMultipartUpload"`
	Parts   []completePart `xml:"Part"`
}

type completePart struct {
	PartNumber int    `xml:"PartNumber"`
	ETag       string `xml:"ETag"`
}

func (c *Client) upCommit(ctx context.Context, pre upPreResp, etags []string) error {
	timeStr := time.Now().UTC().Format(http.TimeFormat)
	upload := completeMultipartUpload{Parts: make([]completePart, 0, len(etags))}
	for i, e := range etags {
		upload.Parts = append(upload.Parts, completePart{PartNumber: i + 1, ETag: e})
	}
	bodyBytes, err := xml.Marshal(upload)
	if err != nil {
		return err
	}
	body := append([]byte(xml.Header), bodyBytes...)
	m := md5.New()
	_, _ = m.Write(body)
	contentMD5 := base64.StdEncoding.EncodeToString(m.Sum(nil))
	cb64, err := callbackBase64(pre)
	if err != nil {
		return err
	}
	authMeta := fmt.Sprintf("POST\n%s\napplication/xml\n%s\nx-oss-callback:%s\nx-oss-date:%s\nx-oss-user-agent:aliyun-sdk-js/6.6.1 Chrome 98.0.4758.80 on Windows 10 64-bit\n/%s/%s?uploadId=%s",
		contentMD5, timeStr, cb64, timeStr, pre.Data.Bucket, pre.Data.ObjKey, pre.Data.UploadID)
	data := map[string]any{"auth_info": pre.Data.AuthInfo, "auth_meta": authMeta, "task_id": pre.Data.TaskID}
	var ar upAuthResp
	if _, err := c.request(ctx, "/file/upload/auth", http.MethodPost, nil, data, &ar); err != nil {
		return err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.ossURL(pre), bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.Header.Set("Authorization", ar.Data.AuthKey)
	req.Header.Set("Content-MD5", contentMD5)
	req.Header.Set("Content-Type", "application/xml")
	req.Header.Set("Referer", "https://pan.quark.cn/")
	req.Header.Set("x-oss-callback", cb64)
	req.Header.Set("x-oss-date", timeStr)
	req.Header.Set("x-oss-user-agent", "aliyun-sdk-js/6.6.1 Chrome 98.0.4758.80 on Windows 10 64-bit")
	q := req.URL.Query()
	q.Set("uploadId", pre.Data.UploadID)
	req.URL.RawQuery = q.Encode()
	resp, err := c.http.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK && resp.StatusCode != http.StatusNonAuthoritativeInfo {
		b, _ := io.ReadAll(resp.Body)
		return fmt.Errorf("up status: %d, error: %s", resp.StatusCode, string(b))
	}
	return nil
}

func (c *Client) upFinish(ctx context.Context, pre upPreResp) error {
	data := map[string]any{"obj_key": pre.Data.ObjKey, "task_id": pre.Data.TaskID}
	_, err := c.request(ctx, "/file/upload/finish", http.MethodPost, nil, data, nil)
	if err != nil {
		return err
	}
	time.Sleep(time.Second)
	return nil
}

func normalizeETag(e string) string {
	return strings.Trim(e, "\"")
}
