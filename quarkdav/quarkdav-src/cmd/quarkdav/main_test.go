package main

import "testing"

func TestPublicURLIPv4(t *testing.T) {
	got := publicURL("127.0.0.1:5244", "/dav")
	if got != "http://127.0.0.1:5244/dav/" {
		t.Fatalf("got %q", got)
	}
}

func TestPublicURLIPv6(t *testing.T) {
	got := publicURL("[::1]:5244", "/dav")
	if got != "http://[::1]:5244/dav/" {
		t.Fatalf("got %q", got)
	}
}
