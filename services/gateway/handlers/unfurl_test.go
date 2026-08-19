package handlers

import (
	"net/url"
	"testing"
)

func TestCleanHTMLText(t *testing.T) {
	tests := []struct {
		input    string
		expected string
	}{
		{"Hello &amp; World", "Hello & World"},
		{"&lt;script&gt;alert(1)&lt;/script&gt;", "<script>alert(1)</script>"},
		{"&quot;GoChat&quot; &#39;Platform&#39;", "\"GoChat\" 'Platform'"},
		{"  Clean text   ", "Clean text"},
	}

	for _, tt := range tests {
		got := cleanHTMLText(tt.input)
		if got != tt.expected {
			t.Errorf("cleanHTMLText(%q) = %q, expected %q", tt.input, got, tt.expected)
		}
	}
}

func TestResolveRelativeURL(t *testing.T) {
	baseURL, _ := url.Parse("https://example.com/blog/article-1")

	tests := []struct {
		target   string
		expected string
	}{
		{"https://cdn.example.com/image.png", "https://cdn.example.com/image.png"},
		{"//cdn.example.com/image.png", "https://cdn.example.com/image.png"},
		{"/assets/og.jpg", "https://example.com/assets/og.jpg"},
		{"thumbnail.png", "https://example.com/blog/thumbnail.png"},
	}

	for _, tt := range tests {
		got := resolveRelativeURL(baseURL, tt.target)
		if got != tt.expected {
			t.Errorf("resolveRelativeURL(%q) = %q, expected %q", tt.target, got, tt.expected)
		}
	}
}

func TestIsPrivateOrLocalHost(t *testing.T) {
	tests := []struct {
		host     string
		expected bool
	}{
		{"localhost", true},
		{"app.localhost", true},
		{"internal.local", true},
		{"127.0.0.1", true},
		{"192.168.1.1", true},
		{"10.0.0.1", true},
		{"169.254.169.254", true}, // AWS/cloud metadata IP
		{"example.com", false},
		{"github.com", false},
		{"8.8.8.8", false},
	}

	for _, tt := range tests {
		got := isPrivateOrLocalHost(tt.host)
		if got != tt.expected {
			t.Errorf("isPrivateOrLocalHost(%q) = %v, expected %v", tt.host, got, tt.expected)
		}
	}
}
