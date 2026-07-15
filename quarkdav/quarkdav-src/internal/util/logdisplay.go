package util

import (
	"fmt"
	"net/url"
	"strings"
	"unicode"
)

// DisplayPath returns a log-friendly version of a WebDAV path.
//
// The path is URL-unescaped when possible, split into path segments, and each
// segment is fully escaped. High-Unicode names, including rclone crypt
// base32768 output, are written completely instead of being summarized.
func DisplayPath(raw string) string {
	if raw == "" {
		return "/"
	}
	if unescaped, err := url.PathUnescape(raw); err == nil {
		raw = unescaped
	}
	parts := strings.Split(raw, "/")
	for i, part := range parts {
		if part == "" {
			continue
		}
		parts[i] = DisplayPathSegment(part)
	}
	out := strings.Join(parts, "/")
	if out == "" {
		return "/"
	}
	return out
}

// DisplayPathSegment formats one path segment for logs.
func DisplayPathSegment(s string) string {
	if s == "" {
		return s
	}
	return EscapeLogString(s)
}

// EscapeLogString returns s with control characters, backslashes, and all
// non-ASCII runes escaped. It never truncates or summarizes the input.
func EscapeLogString(s string) string {
	var b strings.Builder
	for _, r := range s {
		writeEscapedRune(&b, r)
	}
	return b.String()
}

func writeEscapedRune(b *strings.Builder, r rune) {
	switch {
	case r == '\\':
		b.WriteString(`\\`)
	case r == '\n':
		b.WriteString(`\n`)
	case r == '\r':
		b.WriteString(`\r`)
	case r == '\t':
		b.WriteString(`\t`)
	case r < 0x20 || r == 0x7f || !unicode.IsPrint(r) || r > 0x7f:
		if r <= 0xff {
			fmt.Fprintf(b, `\x%02X`, r)
		} else if r <= 0xffff {
			fmt.Fprintf(b, `\u%04X`, r)
		} else {
			fmt.Fprintf(b, `\U%08X`, r)
		}
	default:
		b.WriteRune(r)
	}
}

// FormatBytes formats an integer byte count with comma group separators.
func FormatBytes(n int64) string {
	negative := n < 0
	if negative {
		n = -n
	}
	s := fmt.Sprintf("%d", n)
	if len(s) <= 3 {
		if negative {
			return "-" + s
		}
		return s
	}
	first := len(s) % 3
	if first == 0 {
		first = 3
	}
	var b strings.Builder
	if negative {
		b.WriteByte('-')
	}
	b.WriteString(s[:first])
	for i := first; i < len(s); i += 3 {
		b.WriteByte(',')
		b.WriteString(s[i : i+3])
	}
	return b.String()
}
