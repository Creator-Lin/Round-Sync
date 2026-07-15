package app

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

func TestLoadFromFlagsNormalizesCookieOnlyConfig(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "runtime.json")
	raw := Config{
		Listen:          "[::]:7777",
		Prefix:          "dav/",
		CacheTTLSeconds: 0,
		Driver:          "tv",
		Cookie: CookieConfig{
			Cookie:         "foo=bar",
			RootID:         "",
			Brand:          "UC",
			OrderDirection: "sideways",
		},
	}
	data, err := json.Marshal(raw)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, data, 0600); err != nil {
		t.Fatal(err)
	}

	rt, err := LoadFromFlags([]string{"--config", path})
	if err != nil {
		t.Fatal(err)
	}
	if rt.Config.Driver != "cookie" {
		t.Fatalf("driver=%q", rt.Config.Driver)
	}
	if rt.Config.Prefix != "/dav" {
		t.Fatalf("prefix=%q", rt.Config.Prefix)
	}
	if rt.Config.CacheTTLSeconds != 15 {
		t.Fatalf("ttl=%d", rt.Config.CacheTTLSeconds)
	}
	if rt.Config.Cookie.RootID != "0" {
		t.Fatalf("root=%q", rt.Config.Cookie.RootID)
	}
	if rt.Config.Cookie.Brand != "uc" {
		t.Fatalf("brand=%q", rt.Config.Cookie.Brand)
	}
	if rt.Config.Cookie.OrderDirection != "asc" {
		t.Fatalf("direction=%q", rt.Config.Cookie.OrderDirection)
	}
}

func TestRuntimeSavePersistsRefreshedCookie(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "runtime.json")
	rt := &Runtime{ConfigPath: path, Config: DefaultConfig()}
	rt.Config.Cookie.Cookie = "__puus=refreshed"
	if err := rt.Save(); err != nil {
		t.Fatal(err)
	}

	loaded, err := LoadFromFlags([]string{"--config", path})
	if err != nil {
		t.Fatal(err)
	}
	if loaded.Config.Cookie.Cookie != "__puus=refreshed" {
		t.Fatalf("cookie=%q", loaded.Config.Cookie.Cookie)
	}
}
