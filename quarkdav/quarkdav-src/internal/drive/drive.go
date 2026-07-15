package drive

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"strings"
	"time"
)

var (
	ErrNotFound       = errors.New("object not found")
	ErrAlreadyExists  = errors.New("object already exists")
	ErrUnsupported    = errors.New("operation not supported")
	ErrNotDir         = errors.New("not a directory")
	ErrIsDir          = errors.New("is a directory")
	ErrLengthRequired = errors.New("upload length required")
)

type Entry struct {
	ID          string
	ParentID    string
	Name        string
	Size        int64
	ModTime     time.Time
	CreateTime  time.Time
	IsDir       bool
	Category    int
	ContentHash string
	Thumbnail   string
}

func (e *Entry) Clone() *Entry {
	if e == nil {
		return nil
	}
	cp := *e
	return &cp
}

func (e *Entry) ETag() string {
	if e == nil {
		return `"0"`
	}
	return fmt.Sprintf(`"%x-%x"`, e.ModTime.UnixNano(), e.Size)
}

func (e *Entry) DisplayName() string {
	if e == nil || e.Name == "" {
		return ""
	}
	return e.Name
}

type Link struct {
	URL           string
	Header        http.Header
	ContentLength int64
}

type Upload struct {
	Name       string
	MIME       string
	Size       int64
	ModTime    time.Time
	CreateTime time.Time
	MD5        string
	SHA1       string
	Path       string
}

func (u *Upload) Open() (*os.File, error) {
	return os.Open(u.Path)
}

func (u *Upload) CloseAndRemove() error {
	if u == nil || u.Path == "" {
		return nil
	}
	return os.Remove(u.Path)
}

type Progress func(percent float64)

// StreamUploader can upload a request body directly without first caching it
// into a local temporary file. Implementations must consume exactly up.Size
// bytes from r or return an error.
type StreamUploader interface {
	PutStream(ctx context.Context, parentID string, up *Upload, r io.Reader, progress Progress) error
}

type Details struct {
	TotalSpace int64 `json:"total_space"`
	UsedSpace  int64 `json:"used_space"`
}

type Driver interface {
	Name() string
	RootID() string
	Init(ctx context.Context) error
	Writable() bool
	List(ctx context.Context, parentID string) ([]*Entry, error)
	Link(ctx context.Context, file *Entry, requestHeader http.Header) (*Link, error)
	MakeDir(ctx context.Context, parentID, name string) error
	Move(ctx context.Context, src *Entry, dstParent *Entry) error
	Rename(ctx context.Context, src *Entry, newName string) error
	Remove(ctx context.Context, obj *Entry) error
	Put(ctx context.Context, parentID string, up *Upload, progress Progress) error
	Details(ctx context.Context) (*Details, error)
}

func IsNotFound(err error) bool    { return errors.Is(err, ErrNotFound) }
func IsUnsupported(err error) bool { return errors.Is(err, ErrUnsupported) }

func CleanRootID(root string) string {
	root = strings.TrimSpace(root)
	if root == "" {
		return "0"
	}
	return root
}
