package handlers

import (
	"context"
	"crypto/tls"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"regexp"
	"strings"
	"sync"
	"time"

	"github.com/gin-gonic/gin"
	"go.uber.org/zap"
)

// LinkPreview holds extracted OpenGraph & meta information from a URL.
type LinkPreview struct {
	URL         string `json:"url"`
	Title       string `json:"title,omitempty"`
	Description string `json:"description,omitempty"`
	Image       string `json:"image,omitempty"`
	SiteName    string `json:"site_name,omitempty"`
	Favicon     string `json:"favicon,omitempty"`
	MediaType   string `json:"media_type,omitempty"` // "article", "website", "video", "image"
}

var (
	// In-memory cache for unfurled URLs: url -> {preview, expiresAt}
	previewCache sync.Map

	// Regexes for HTML meta tags
	metaOgTitleRegex       = regexp.MustCompile(`(?i)<meta\s+[^>]*property=["']og:title["'][^>]*content=["']([^"']*)["']`)
	metaOgTitleAltRegex    = regexp.MustCompile(`(?i)<meta\s+[^>]*content=["']([^"']*)["'][^>]*property=["']og:title["']`)
	metaOgDescRegex        = regexp.MustCompile(`(?i)<meta\s+[^>]*property=["']og:description["'][^>]*content=["']([^"']*)["']`)
	metaOgDescAltRegex     = regexp.MustCompile(`(?i)<meta\s+[^>]*content=["']([^"']*)["'][^>]*property=["']og:description["']`)
	metaOgImageRegex       = regexp.MustCompile(`(?i)<meta\s+[^>]*property=["']og:image["'][^>]*content=["']([^"']*)["']`)
	metaOgImageAltRegex    = regexp.MustCompile(`(?i)<meta\s+[^>]*content=["']([^"']*)["'][^>]*property=["']og:image["']`)
	metaOgSiteNameRegex    = regexp.MustCompile(`(?i)<meta\s+[^>]*property=["']og:site_name["'][^>]*content=["']([^"']*)["']`)
	metaTwitterTitleRegex  = regexp.MustCompile(`(?i)<meta\s+[^>]*name=["']twitter:title["'][^>]*content=["']([^"']*)["']`)
	metaTwitterDescRegex   = regexp.MustCompile(`(?i)<meta\s+[^>]*name=["']twitter:description["'][^>]*content=["']([^"']*)["']`)
	metaTwitterImageRegex  = regexp.MustCompile(`(?i)<meta\s+[^>]*name=["']twitter:image["'][^>]*content=["']([^"']*)["']`)
	htmlTitleRegex         = regexp.MustCompile(`(?i)<title[^>]*>([^<]+)</title>`)
	metaStandardDescRegex  = regexp.MustCompile(`(?i)<meta\s+[^>]*name=["']description["'][^>]*content=["']([^"']*)["']`)
	htmlFaviconRegex       = regexp.MustCompile(`(?i)<link\s+[^>]*rel=["'](?:shortcut\s+)?icon["'][^>]*href=["']([^"']*)["']`)
)

type cachedItem struct {
	preview   LinkPreview
	expiresAt time.Time
}

// UnfurlURL extracts rich link preview metadata from a shared web URL.
func (h *ChatHandler) UnfurlURL(c *gin.Context) {
	targetURL := c.Query("url")
	if targetURL == "" {
		var body struct {
			URL string `json:"url"`
		}
		if err := c.ShouldBindJSON(&body); err == nil {
			targetURL = body.URL
		}
	}

	targetURL = strings.TrimSpace(targetURL)
	if targetURL == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "url parameter is required"})
		return
	}

	if !strings.HasPrefix(targetURL, "http://") && !strings.HasPrefix(targetURL, "https://") {
		targetURL = "https://" + targetURL
	}

	// Check in-memory cache
	if item, ok := previewCache.Load(targetURL); ok {
		ci := item.(cachedItem)
		if time.Now().Before(ci.expiresAt) {
			c.JSON(http.StatusOK, ci.preview)
			return
		}
		previewCache.Delete(targetURL)
	}

	// Validate URL & SSRF prevention
	parsed, err := url.Parse(targetURL)
	if err != nil || parsed.Host == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid URL format"})
		return
	}

	if isPrivateOrLocalHost(parsed.Hostname()) {
		c.JSON(http.StatusBadRequest, gin.H{"error": "cannot unfurl private or local network addresses"})
		return
	}

	preview, err := fetchAndExtractPreview(targetURL, parsed, h.log)
	if err != nil {
		// Fallback minimal preview with hostname
		fallback := LinkPreview{
			URL:      targetURL,
			SiteName: parsed.Hostname(),
			Favicon:  fmt.Sprintf("https://www.google.com/s2/favicons?domain=%s&sz=64", parsed.Hostname()),
		}
		c.JSON(http.StatusOK, fallback)
		return
	}

	// Store in cache for 12 hours
	previewCache.Store(targetURL, cachedItem{
		preview:   preview,
		expiresAt: time.Now().Add(12 * time.Hour),
	})

	c.JSON(http.StatusOK, preview)
}

func fetchAndExtractPreview(targetURL string, parsed *url.URL, log *zap.Logger) (LinkPreview, error) {
	client := &http.Client{
		Timeout: 4 * time.Second,
		Transport: &http.Transport{
			TLSClientConfig: &tls.Config{InsecureSkipVerify: false},
			DialContext: (&net.Dialer{
				Timeout: 3 * time.Second,
			}).DialContext,
		},
	}

	req, err := http.NewRequestWithContext(context.Background(), "GET", targetURL, nil)
	if err != nil {
		return LinkPreview{}, err
	}
	req.Header.Set("User-Agent", "Mozilla/5.0 (compatible; GoChatBot/1.0; +https://gochat.app/bot)")
	req.Header.Set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")

	resp, err := client.Do(req)
	if err != nil {
		return LinkPreview{}, err
	}
	defer resp.Body.Close()

	// Read up to 512KB of HTML
	lr := io.LimitReader(resp.Body, 512*1024)
	bodyBytes, err := io.ReadAll(lr)
	if err != nil {
		return LinkPreview{}, err
	}
	html := string(bodyBytes)

	preview := LinkPreview{
		URL:      targetURL,
		SiteName: parsed.Hostname(),
	}

	// Extract Title
	if match := metaOgTitleRegex.FindStringSubmatch(html); len(match) > 1 {
		preview.Title = cleanHTMLText(match[1])
	} else if match := metaOgTitleAltRegex.FindStringSubmatch(html); len(match) > 1 {
		preview.Title = cleanHTMLText(match[1])
	} else if match := metaTwitterTitleRegex.FindStringSubmatch(html); len(match) > 1 {
		preview.Title = cleanHTMLText(match[1])
	} else if match := htmlTitleRegex.FindStringSubmatch(html); len(match) > 1 {
		preview.Title = cleanHTMLText(match[1])
	}

	// Extract Description
	if match := metaOgDescRegex.FindStringSubmatch(html); len(match) > 1 {
		preview.Description = cleanHTMLText(match[1])
	} else if match := metaOgDescAltRegex.FindStringSubmatch(html); len(match) > 1 {
		preview.Description = cleanHTMLText(match[1])
	} else if match := metaTwitterDescRegex.FindStringSubmatch(html); len(match) > 1 {
		preview.Description = cleanHTMLText(match[1])
	} else if match := metaStandardDescRegex.FindStringSubmatch(html); len(match) > 1 {
		preview.Description = cleanHTMLText(match[1])
	}

	// Extract Image
	if match := metaOgImageRegex.FindStringSubmatch(html); len(match) > 1 {
		preview.Image = resolveRelativeURL(parsed, match[1])
	} else if match := metaOgImageAltRegex.FindStringSubmatch(html); len(match) > 1 {
		preview.Image = resolveRelativeURL(parsed, match[1])
	} else if match := metaTwitterImageRegex.FindStringSubmatch(html); len(match) > 1 {
		preview.Image = resolveRelativeURL(parsed, match[1])
	}

	// Extract Site Name
	if match := metaOgSiteNameRegex.FindStringSubmatch(html); len(match) > 1 {
		preview.SiteName = cleanHTMLText(match[1])
	}

	// Extract Favicon
	if match := htmlFaviconRegex.FindStringSubmatch(html); len(match) > 1 {
		preview.Favicon = resolveRelativeURL(parsed, match[1])
	}
	if preview.Favicon == "" {
		preview.Favicon = fmt.Sprintf("https://www.google.com/s2/favicons?domain=%s&sz=64", parsed.Hostname())
	}

	return preview, nil
}

func cleanHTMLText(s string) string {
	s = strings.ReplaceAll(s, "&amp;", "&")
	s = strings.ReplaceAll(s, "&lt;", "<")
	s = strings.ReplaceAll(s, "&gt;", ">")
	s = strings.ReplaceAll(s, "&quot;", "\"")
	s = strings.ReplaceAll(s, "&#39;", "'")
	return strings.TrimSpace(s)
}

func resolveRelativeURL(base *url.URL, target string) string {
	target = strings.TrimSpace(target)
	if target == "" {
		return ""
	}
	if strings.HasPrefix(target, "//") {
		return base.Scheme + ":" + target
	}
	tURL, err := url.Parse(target)
	if err != nil {
		return target
	}
	return base.ResolveReference(tURL).String()
}

// isPrivateOrLocalHost checks for private RFC1918 / loopback ranges.
func isPrivateOrLocalHost(host string) bool {
	h := strings.ToLower(host)
	if h == "localhost" || strings.HasSuffix(h, ".localhost") || strings.HasSuffix(h, ".local") {
		return true
	}
	ip := net.ParseIP(host)
	if ip == nil {
		return false
	}
	return ip.IsLoopback() || ip.IsPrivate() || ip.IsLinkLocalUnicast() || ip.IsUnspecified()
}
