package app

import (
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

// Config is the cookie-only QuarkDav runtime configuration used by Round-Sync.
// Android-only service metadata is deliberately kept outside this file so the
// Go process can safely persist refreshed cookie values without losing it.
type Config struct {
	Listen          string       `json:"listen"`
	Prefix          string       `json:"prefix"`
	Username        string       `json:"username"`
	Password        string       `json:"password"`
	NoAuth          bool         `json:"no_auth"`
	CacheTTLSeconds int          `json:"cache_ttl_seconds"`
	TempDir         string       `json:"temp_dir"`
	Driver          string       `json:"driver"`
	Cookie          CookieConfig `json:"cookie"`
}

type CookieConfig struct {
	Cookie                string `json:"cookie"`
	RootID                string `json:"root_id"`
	Brand                 string `json:"brand"`
	OrderBy               string `json:"order_by"`
	OrderDirection        string `json:"order_direction"`
	UseTranscodingAddress bool   `json:"use_transcoding_address"`
	OnlyListVideoFile     bool   `json:"only_list_video_file"`
}

func DefaultConfig() Config {
	return Config{
		Listen:          "127.0.0.1:5244",
		Prefix:          "/dav",
		Username:        "quark",
		Password:        "quark",
		NoAuth:          false,
		CacheTTLSeconds: 15,
		TempDir:         "",
		Driver:          "cookie",
		Cookie: CookieConfig{
			RootID:         "0",
			Brand:          "quark",
			OrderBy:        "none",
			OrderDirection: "asc",
		},
	}
}

type Runtime struct {
	ConfigPath string
	Config     Config
}

func LoadFromFlags(args []string) (*Runtime, error) {
	cfg := DefaultConfig()
	fs := flag.NewFlagSet("quarkdav", flag.ContinueOnError)
	configPath := fs.String("config", "", "path to config file")
	listen := fs.String("listen", "", "HTTP listen address")
	prefix := fs.String("prefix", "", "WebDAV URL prefix")
	cookie := fs.String("cookie", "", "Quark/UC cookie")
	user := fs.String("user", "", "WebDAV username")
	pass := fs.String("pass", "", "WebDAV password")
	noAuth := fs.Bool("no-auth", false, "disable WebDAV basic auth")
	rootID := fs.String("root", "", "Quark root folder id")

	if err := fs.Parse(args); err != nil {
		return nil, err
	}
	if strings.TrimSpace(*configPath) == "" {
		return nil, errors.New("config path is required")
	}

	b, err := os.ReadFile(*configPath)
	if err != nil {
		return nil, fmt.Errorf("read config: %w", err)
	}
	if err := json.Unmarshal(b, &cfg); err != nil {
		return nil, fmt.Errorf("parse config: %w", err)
	}

	if *listen != "" {
		cfg.Listen = *listen
	}
	if *prefix != "" {
		cfg.Prefix = *prefix
	}
	if *cookie != "" {
		cfg.Cookie.Cookie = *cookie
	}
	if *user != "" {
		cfg.Username = *user
	}
	if *pass != "" {
		cfg.Password = *pass
	}
	if *noAuth {
		cfg.NoAuth = true
	}
	if *rootID != "" {
		cfg.Cookie.RootID = *rootID
	}

	normalize(&cfg)
	return &Runtime{ConfigPath: *configPath, Config: cfg}, nil
}

func normalize(c *Config) {
	if strings.TrimSpace(c.Listen) == "" {
		c.Listen = "127.0.0.1:5244"
	}
	if strings.TrimSpace(c.Prefix) == "" {
		c.Prefix = "/dav"
	}
	if !strings.HasPrefix(c.Prefix, "/") {
		c.Prefix = "/" + c.Prefix
	}
	c.Prefix = strings.TrimRight(c.Prefix, "/")
	if c.Prefix == "" {
		c.Prefix = "/dav"
	}
	c.Driver = "cookie"
	if c.CacheTTLSeconds <= 0 {
		c.CacheTTLSeconds = 15
	}
	if c.Cookie.RootID == "" {
		c.Cookie.RootID = "0"
	}
	c.Cookie.Brand = strings.ToLower(strings.TrimSpace(c.Cookie.Brand))
	if c.Cookie.Brand != "uc" {
		c.Cookie.Brand = "quark"
	}
	if c.Cookie.OrderBy == "" {
		c.Cookie.OrderBy = "none"
	}
	c.Cookie.OrderDirection = strings.ToLower(strings.TrimSpace(c.Cookie.OrderDirection))
	if c.Cookie.OrderDirection != "desc" {
		c.Cookie.OrderDirection = "asc"
	}
}

func (r *Runtime) Save() error {
	if r == nil || strings.TrimSpace(r.ConfigPath) == "" {
		return nil
	}
	normalize(&r.Config)
	b, err := json.MarshalIndent(r.Config, "", "  ")
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(r.ConfigPath), 0700); err != nil {
		return err
	}
	tmp := r.ConfigPath + ".tmp"
	if err := os.WriteFile(tmp, b, 0600); err != nil {
		return err
	}
	return os.Rename(tmp, r.ConfigPath)
}
