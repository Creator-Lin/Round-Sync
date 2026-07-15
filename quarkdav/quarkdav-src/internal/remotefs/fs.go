package remotefs

import (
	"context"
	"fmt"
	"io"
	"net/http"
	"path"
	"sort"
	"strings"
	"sync"
	"time"

	"quarkdav/internal/drive"
	"quarkdav/internal/util"
)

type FS struct {
	Driver  drive.Driver
	TTL     time.Duration
	TempDir string

	mu    sync.Mutex
	cache map[string]cacheEntry
}

type cacheEntry struct {
	at    time.Time
	items []*drive.Entry
}

func New(driver drive.Driver, ttl time.Duration, tempDir string) *FS {
	return &FS{Driver: driver, TTL: ttl, TempDir: tempDir, cache: make(map[string]cacheEntry)}
}

func (fs *FS) Root() *drive.Entry {
	now := time.Now()
	return &drive.Entry{ID: fs.Driver.RootID(), Name: "", IsDir: true, ModTime: now, CreateTime: now}
}

func Clean(p string) string {
	if p == "" || p[0] != '/' {
		p = "/" + p
	}
	return path.Clean(p)
}

func split(p string) []string {
	p = strings.Trim(Clean(p), "/")
	if p == "" {
		return nil
	}
	raw := strings.Split(p, "/")
	out := raw[:0]
	for _, s := range raw {
		if s != "" {
			out = append(out, s)
		}
	}
	return out
}

func (fs *FS) Stat(ctx context.Context, p string) (*drive.Entry, error) {
	p = Clean(p)
	if p == "/" {
		return fs.Root(), nil
	}
	parentPath := path.Dir(p)
	name := path.Base(p)
	parent, err := fs.Stat(ctx, parentPath)
	if err != nil {
		return nil, err
	}
	if !parent.IsDir {
		return nil, drive.ErrNotDir
	}
	items, err := fs.ListByID(ctx, parent.ID)
	if err != nil {
		return nil, err
	}
	for _, e := range items {
		if e.Name == name {
			cp := e.Clone()
			return cp, nil
		}
	}
	return nil, drive.ErrNotFound
}

func (fs *FS) ResolveParent(ctx context.Context, p string) (*drive.Entry, string, error) {
	p = Clean(p)
	if p == "/" {
		return nil, "", drive.ErrAlreadyExists
	}
	parent, err := fs.Stat(ctx, path.Dir(p))
	if err != nil {
		return nil, "", err
	}
	if !parent.IsDir {
		return nil, "", drive.ErrNotDir
	}
	return parent, path.Base(p), nil
}

func (fs *FS) ListPath(ctx context.Context, p string) ([]*drive.Entry, error) {
	e, err := fs.Stat(ctx, p)
	if err != nil {
		return nil, err
	}
	if !e.IsDir {
		return nil, drive.ErrNotDir
	}
	return fs.ListByID(ctx, e.ID)
}

func (fs *FS) ListByID(ctx context.Context, id string) ([]*drive.Entry, error) {
	if fs.TTL > 0 {
		fs.mu.Lock()
		ce, ok := fs.cache[id]
		if ok && time.Since(ce.at) < fs.TTL {
			out := cloneEntries(ce.items)
			fs.mu.Unlock()
			return out, nil
		}
		fs.mu.Unlock()
	}
	items, err := fs.Driver.List(ctx, id)
	if err != nil {
		return nil, err
	}
	sort.SliceStable(items, func(i, j int) bool {
		if items[i].IsDir != items[j].IsDir {
			return items[i].IsDir
		}
		return strings.ToLower(items[i].Name) < strings.ToLower(items[j].Name)
	})
	fs.mu.Lock()
	fs.cache[id] = cacheEntry{at: time.Now(), items: cloneEntries(items)}
	fs.mu.Unlock()
	return cloneEntries(items), nil
}

func cloneEntries(in []*drive.Entry) []*drive.Entry {
	out := make([]*drive.Entry, len(in))
	for i, e := range in {
		out[i] = e.Clone()
	}
	return out
}

func (fs *FS) Invalidate(ids ...string) {
	fs.mu.Lock()
	defer fs.mu.Unlock()
	if len(ids) == 0 {
		fs.cache = make(map[string]cacheEntry)
		return
	}
	for _, id := range ids {
		delete(fs.cache, id)
	}
}

func (fs *FS) MakeDir(ctx context.Context, p string) error {
	parent, name, err := fs.ResolveParent(ctx, p)
	if err != nil {
		return err
	}
	if _, err := fs.Stat(ctx, p); err == nil {
		return drive.ErrAlreadyExists
	}
	if err := fs.Driver.MakeDir(ctx, parent.ID, name); err != nil {
		return err
	}
	fs.Invalidate(parent.ID)
	return nil
}

func (fs *FS) Remove(ctx context.Context, p string) error {
	obj, err := fs.Stat(ctx, p)
	if err != nil {
		return err
	}
	parent, _, err := fs.ResolveParent(ctx, p)
	if err != nil {
		return err
	}
	if err := fs.Driver.Remove(ctx, obj); err != nil {
		return err
	}
	fs.Invalidate(parent.ID, obj.ID)
	return nil
}

func (fs *FS) Move(ctx context.Context, srcPath, dstPath string, overwrite bool) error {
	srcPath, dstPath = Clean(srcPath), Clean(dstPath)
	if srcPath == "/" {
		return fmt.Errorf("cannot move root")
	}
	src, err := fs.Stat(ctx, srcPath)
	if err != nil {
		return err
	}
	srcParent, _, err := fs.ResolveParent(ctx, srcPath)
	if err != nil {
		return err
	}
	dstParent, dstName, err := fs.ResolveParent(ctx, dstPath)
	if err != nil {
		return err
	}
	if existing, err := fs.Stat(ctx, dstPath); err == nil {
		if !overwrite {
			return drive.ErrAlreadyExists
		}
		if existing.ID == src.ID {
			return fmt.Errorf("destination equals source")
		}
		if err := fs.Driver.Remove(ctx, existing); err != nil {
			return err
		}
		fs.Invalidate(dstParent.ID, existing.ID)
	}
	if srcParent.ID != dstParent.ID {
		if err := fs.Driver.Move(ctx, src, dstParent); err != nil {
			return err
		}
		fs.Invalidate(srcParent.ID, dstParent.ID)
		if src.Name != dstName {
			moved := src.Clone()
			moved.Name = src.Name
			if fresh, err := fs.findByName(ctx, dstParent.ID, src.Name); err == nil {
				moved = fresh
			}
			if err := fs.Driver.Rename(ctx, moved, dstName); err != nil {
				return err
			}
			fs.Invalidate(dstParent.ID)
		}
	} else if src.Name != dstName {
		if err := fs.Driver.Rename(ctx, src, dstName); err != nil {
			return err
		}
		fs.Invalidate(srcParent.ID)
	}
	return nil
}

func (fs *FS) findByName(ctx context.Context, parentID, name string) (*drive.Entry, error) {
	fs.Invalidate(parentID)
	items, err := fs.ListByID(ctx, parentID)
	if err != nil {
		return nil, err
	}
	for _, e := range items {
		if e.Name == name {
			return e, nil
		}
	}
	return nil, drive.ErrNotFound
}

func (fs *FS) Put(ctx context.Context, dstPath string, r io.Reader, size int64, mime string, modTime, createTime time.Time) (*drive.Entry, error) {
	parent, name, err := fs.ResolveParent(ctx, dstPath)
	if err != nil {
		return nil, err
	}
	if mime == "" {
		mime = util.MIMEByName(name)
	}

	if streamer, ok := fs.Driver.(drive.StreamUploader); ok {
		if size < 0 {
			return nil, drive.ErrLengthRequired
		}
		up := &drive.Upload{Name: name, MIME: mime, Size: size, ModTime: modTime, CreateTime: createTime}
		old, tempName, err := fs.prepareOverwrite(ctx, dstPath, parent, name)
		if err != nil {
			return nil, err
		}
		err = streamer.PutStream(ctx, parent.ID, up, r, nil)
		if err != nil {
			fs.restoreOverwrite(ctx, parent, old, tempName, name)
			fs.Invalidate(parent.ID)
			return nil, err
		}
		fs.cleanupOverwrite(ctx, parent, old, tempName)
		fs.Invalidate(parent.ID)
		if fresh, err := fs.findByName(ctx, parent.ID, name); err == nil {
			return fresh, nil
		}
		return &drive.Entry{Name: name, Size: size, IsDir: false, ModTime: modTime, CreateTime: createTime}, nil
	}

	tmp, md5Hex, sha1Hex, realSize, err := util.HashReaderToTemp(fs.TempDir, r, nil)
	if err != nil {
		return nil, err
	}
	up := &drive.Upload{Name: name, MIME: mime, Size: realSize, ModTime: modTime, CreateTime: createTime, MD5: md5Hex, SHA1: sha1Hex, Path: tmp}
	defer up.CloseAndRemove()
	old, tempName, err := fs.prepareOverwrite(ctx, dstPath, parent, name)
	if err != nil {
		return nil, err
	}
	err = fs.Driver.Put(ctx, parent.ID, up, nil)
	if err != nil {
		fs.restoreOverwrite(ctx, parent, old, tempName, name)
		fs.Invalidate(parent.ID)
		return nil, err
	}
	fs.cleanupOverwrite(ctx, parent, old, tempName)
	fs.Invalidate(parent.ID)
	if fresh, err := fs.findByName(ctx, parent.ID, name); err == nil {
		return fresh, nil
	}
	return &drive.Entry{Name: name, Size: realSize, IsDir: false, ModTime: modTime, CreateTime: createTime}, nil
}

func (fs *FS) prepareOverwrite(ctx context.Context, dstPath string, parent *drive.Entry, name string) (old *drive.Entry, tempName string, err error) {
	tempName = name + ".openlist_to_delete"
	if existing, err := fs.Stat(ctx, dstPath); err == nil {
		old = existing
		if existing.IsDir {
			return nil, "", drive.ErrIsDir
		}
		if existing.Size == 0 {
			if err := fs.Driver.Remove(ctx, existing); err != nil {
				return nil, "", fmt.Errorf("remove existing zero-byte file: %w", err)
			}
		} else {
			if err := fs.Driver.Rename(ctx, existing, tempName); err != nil {
				return nil, "", err
			}
		}
		fs.Invalidate(parent.ID)
	}
	return old, tempName, nil
}

func (fs *FS) restoreOverwrite(ctx context.Context, parent *drive.Entry, old *drive.Entry, tempName string, name string) {
	if old != nil && old.Size > 0 {
		if tmpObj, err := fs.findByName(ctx, parent.ID, tempName); err == nil {
			_ = fs.Driver.Rename(ctx, tmpObj, name)
		}
	}
}

func (fs *FS) cleanupOverwrite(ctx context.Context, parent *drive.Entry, old *drive.Entry, tempName string) {
	if old != nil && old.Size > 0 {
		if tmpObj, err := fs.findByName(ctx, parent.ID, tempName); err == nil {
			_ = fs.Driver.Remove(ctx, tmpObj)
		}
	}
}

func (fs *FS) Link(ctx context.Context, p string, h http.Header) (*drive.Link, *drive.Entry, error) {
	e, err := fs.Stat(ctx, p)
	if err != nil {
		return nil, nil, err
	}
	if e.IsDir {
		return nil, e, drive.ErrIsDir
	}
	link, err := fs.Driver.Link(ctx, e, h)
	return link, e, err
}

func (fs *FS) Copy(ctx context.Context, srcPath, dstPath string, overwrite bool, depth int) error {
	src, err := fs.Stat(ctx, srcPath)
	if err != nil {
		return err
	}
	dstParent, dstName, err := fs.ResolveParent(ctx, dstPath)
	if err != nil {
		return err
	}
	if existing, err := fs.Stat(ctx, dstPath); err == nil {
		if !overwrite {
			return drive.ErrAlreadyExists
		}
		if err := fs.Driver.Remove(ctx, existing); err != nil {
			return err
		}
		fs.Invalidate(dstParent.ID, existing.ID)
	}
	if src.IsDir {
		if err := fs.Driver.MakeDir(ctx, dstParent.ID, dstName); err != nil && !drive.IsUnsupported(err) {
			return err
		}
		fs.Invalidate(dstParent.ID)
		if depth == 0 {
			return nil
		}
		children, err := fs.ListPath(ctx, srcPath)
		if err != nil {
			return err
		}
		for _, child := range children {
			childSrc := path.Join(srcPath, child.Name)
			childDst := path.Join(dstPath, child.Name)
			if err := fs.Copy(ctx, childSrc, childDst, true, depth-1); err != nil {
				return err
			}
		}
		return nil
	}
	link, _, err := fs.Link(ctx, srcPath, nil)
	if err != nil {
		return err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, link.URL, nil)
	if err != nil {
		return err
	}
	for k, vals := range link.Header {
		for _, v := range vals {
			req.Header.Add(k, v)
		}
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return fmt.Errorf("download for copy returned %s", resp.Status)
	}
	_, err = fs.Put(ctx, dstPath, resp.Body, src.Size, util.MIMEByName(src.Name), src.ModTime, src.CreateTime)
	return err
}
