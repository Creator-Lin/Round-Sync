package dav

import (
	"regexp"
	"strings"
	"sync"
	"time"

	"quarkdav/internal/remotefs"
	"quarkdav/internal/util"
)

const statusLocked = 423

type LockSystem struct {
	mu    sync.Mutex
	locks map[string]lockInfo
}

type lockInfo struct {
	Path      string
	Token     string
	OwnerXML  string
	ExpiresAt time.Time
	ZeroDepth bool
}

func NewLockSystem() *LockSystem { return &LockSystem{locks: make(map[string]lockInfo)} }

func (ls *LockSystem) Create(path string, ownerXML string, zeroDepth bool, dur time.Duration) string {
	if dur <= 0 {
		dur = time.Hour
	}
	token := "opaquelocktoken:" + util.NewUUID()
	ls.mu.Lock()
	defer ls.mu.Unlock()
	ls.cleanupLocked(time.Now())
	ls.locks[token] = lockInfo{Path: remotefs.Clean(path), Token: token, OwnerXML: ownerXML, ZeroDepth: zeroDepth, ExpiresAt: time.Now().Add(dur)}
	return token
}

func (ls *LockSystem) Unlock(token string) bool {
	token = strings.Trim(token, "<>")
	ls.mu.Lock()
	defer ls.mu.Unlock()
	if _, ok := ls.locks[token]; !ok {
		return false
	}
	delete(ls.locks, token)
	return true
}

func (ls *LockSystem) Refresh(token string, dur time.Duration) (lockInfo, bool) {
	token = strings.Trim(token, "<>")
	if dur <= 0 {
		dur = time.Hour
	}
	ls.mu.Lock()
	defer ls.mu.Unlock()
	ls.cleanupLocked(time.Now())
	li, ok := ls.locks[token]
	if !ok {
		return lockInfo{}, false
	}
	li.ExpiresAt = time.Now().Add(dur)
	ls.locks[token] = li
	return li, true
}

func (ls *LockSystem) Check(paths []string, ifHeader string) bool {
	tokens := parseLockTokens(ifHeader)
	ls.mu.Lock()
	defer ls.mu.Unlock()
	ls.cleanupLocked(time.Now())
	for _, p := range paths {
		p = remotefs.Clean(p)
		for _, l := range ls.locks {
			if !lockCovers(l, p) {
				continue
			}
			if !tokens[l.Token] {
				return false
			}
		}
	}
	return true
}

func (ls *LockSystem) Active(path string) []lockInfo {
	path = remotefs.Clean(path)
	ls.mu.Lock()
	defer ls.mu.Unlock()
	ls.cleanupLocked(time.Now())
	out := make([]lockInfo, 0)
	for _, l := range ls.locks {
		if lockCovers(l, path) {
			out = append(out, l)
		}
	}
	return out
}

func (ls *LockSystem) cleanupLocked(now time.Time) {
	for t, l := range ls.locks {
		if !l.ExpiresAt.IsZero() && now.After(l.ExpiresAt) {
			delete(ls.locks, t)
		}
	}
}

func lockCovers(l lockInfo, p string) bool {
	root := remotefs.Clean(l.Path)
	p = remotefs.Clean(p)
	if l.ZeroDepth {
		return p == root
	}
	return p == root || strings.HasPrefix(p, strings.TrimRight(root, "/")+"/")
}

var tokenRe = regexp.MustCompile(`opaquelocktoken:[^>\s)]+`)

func parseLockTokens(s string) map[string]bool {
	m := map[string]bool{}
	for _, t := range tokenRe.FindAllString(s, -1) {
		m[t] = true
	}
	return m
}
