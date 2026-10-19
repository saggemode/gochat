package handlers

import (
	"context"
	"crypto/tls"
	"encoding/json"
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
	MediaType   string `json:"media_type,omitempty"` // "article", "website", "video", "image", "audio", "map"

	// Enhanced fields for rich provider-specific cards
	Provider   string `json:"provider,omitempty"`    // "youtube", "twitter", "spotify", "tiktok", "instagram", "maps"
	EmbedURL   string `json:"embed_url,omitempty"`   // Embeddable player/frame URL
	AuthorName string `json:"author_name,omitempty"` // Channel name, artist, username
	Duration   string `json:"duration,omitempty"`    // "3:42", "PT3M42S"
	VideoID    string `json:"video_id,omitempty"`    // YouTube video ID, Spotify track ID, etc.
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

	// Provider URL patterns
	youtubeRegex   = regexp.MustCompile(`(?i)(?:youtube\.com/watch\?.*v=|youtu\.be/|youtube\.com/shorts/)([a-zA-Z0-9_-]{11})`)
	spotifyRegex   = regexp.MustCompile(`(?i)open\.spotify\.com/(track|album|playlist|episode)/([a-zA-Z0-9]+)`)
	twitterRegex   = regexp.MustCompile(`(?i)(?:twitter\.com|x\.com)/([^/]+)/status/(\d+)`)
	tiktokRegex    = regexp.MustCompile(`(?i)(?:tiktok\.com|vm\.tiktok\.com)`)
	instagramRegex = regexp.MustCompile(`(?i)instagram\.com/(?:p|reel|tv)/([a-zA-Z0-9_-]+)`)
	mapsRegex      = regexp.MustCompile(`(?i)(?:google\.\w+/maps|maps\.google\.\w+|maps\.apple\.com|goo\.gl/maps)`)
	mapsCoordRegex = regexp.MustCompile(`@(-?\d+\.\d+),(-?\d+\.\d+)`)
	mapsQueryRegex = regexp.MustCompile(`[?&]q=([^&]+)`)
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

	// Try provider-specific extractors first (fast oEmbed APIs)
	preview, extracted := tryProviderExtract(targetURL, parsed, h.log)
	if !extracted {
		// Fallback to generic OG-tag scraping
		var fetchErr error
		preview, fetchErr = fetchAndExtractPreview(targetURL, parsed, h.log)
		if fetchErr != nil {
			// Fallback minimal preview with hostname
			fallback := LinkPreview{
				URL:      targetURL,
				SiteName: parsed.Hostname(),
				Favicon:  fmt.Sprintf("https://www.google.com/s2/favicons?domain=%s&sz=64", parsed.Hostname()),
			}
			c.JSON(http.StatusOK, fallback)
			return
		}
	}

	// Store in cache for 12 hours
	previewCache.Store(targetURL, cachedItem{
		preview:   preview,
		expiresAt: time.Now().Add(12 * time.Hour),
	})

	c.JSON(http.StatusOK, preview)
}

// ── Provider-specific extractors ─────────────────────────────────────────

// tryProviderExtract attempts fast structured extraction for known providers.
// Returns (preview, true) if successful, or (empty, false) to fall back to OG scraping.
func tryProviderExtract(targetURL string, parsed *url.URL, log *zap.Logger) (LinkPreview, bool) {
	// YouTube
	if match := youtubeRegex.FindStringSubmatch(targetURL); len(match) > 1 {
		videoID := match[1]
		preview := extractYouTube(targetURL, videoID, log)
		if preview.Title != "" {
			return preview, true
		}
	}

	// Spotify
	if match := spotifyRegex.FindStringSubmatch(targetURL); len(match) > 2 {
		contentType := match[1] // track, album, playlist, episode
		contentID := match[2]
		preview := extractSpotify(targetURL, contentType, contentID, log)
		if preview.Title != "" {
			return preview, true
		}
	}

	// Twitter / X
	if match := twitterRegex.FindStringSubmatch(targetURL); len(match) > 2 {
		username := match[1]
		tweetID := match[2]
		preview := extractTwitter(targetURL, username, tweetID, log)
		if preview.Title != "" {
			return preview, true
		}
	}

	// Google Maps / Apple Maps
	if mapsRegex.MatchString(targetURL) {
		preview := extractMaps(targetURL, parsed, log)
		return preview, true
	}

	// TikTok (oEmbed)
	if tiktokRegex.MatchString(targetURL) {
		preview := extractTikTok(targetURL, log)
		if preview.Title != "" {
			return preview, true
		}
	}

	// Instagram (oEmbed)
	if match := instagramRegex.FindStringSubmatch(targetURL); len(match) > 1 {
		preview := extractInstagram(targetURL, log)
		if preview.Title != "" {
			return preview, true
		}
	}

	return LinkPreview{}, false
}

// extractYouTube fetches metadata via the public YouTube oEmbed API.
func extractYouTube(targetURL, videoID string, log *zap.Logger) LinkPreview {
	preview := LinkPreview{
		URL:       targetURL,
		Provider:  "youtube",
		SiteName:  "YouTube",
		MediaType: "video",
		VideoID:   videoID,
		EmbedURL:  fmt.Sprintf("https://www.youtube.com/embed/%s", videoID),
		Image:     fmt.Sprintf("https://img.youtube.com/vi/%s/hqdefault.jpg", videoID),
		Favicon:   "https://www.google.com/s2/favicons?domain=youtube.com&sz=64",
	}

	oembed := fetchOEmbed(fmt.Sprintf("https://www.youtube.com/oembed?url=%s&format=json",
		url.QueryEscape(targetURL)), log)
	if oembed != nil {
		if t, ok := oembed["title"].(string); ok && t != "" {
			preview.Title = t
		}
		if a, ok := oembed["author_name"].(string); ok && a != "" {
			preview.AuthorName = a
		}
		if img, ok := oembed["thumbnail_url"].(string); ok && img != "" {
			preview.Image = img
		}
	}
	return preview
}

// extractSpotify fetches metadata via Spotify's public oEmbed API.
func extractSpotify(targetURL, contentType, contentID string, log *zap.Logger) LinkPreview {
	preview := LinkPreview{
		URL:       targetURL,
		Provider:  "spotify",
		SiteName:  "Spotify",
		MediaType: "audio",
		VideoID:   contentID,
		EmbedURL:  fmt.Sprintf("https://open.spotify.com/embed/%s/%s", contentType, contentID),
		Favicon:   "https://www.google.com/s2/favicons?domain=open.spotify.com&sz=64",
	}

	oembed := fetchOEmbed(fmt.Sprintf("https://open.spotify.com/oembed?url=%s",
		url.QueryEscape(targetURL)), log)
	if oembed != nil {
		if t, ok := oembed["title"].(string); ok && t != "" {
			preview.Title = t
		}
		if img, ok := oembed["thumbnail_url"].(string); ok && img != "" {
			preview.Image = img
		}
		// Spotify oEmbed doesn't expose author directly, but title often includes it
		if desc, ok := oembed["description"].(string); ok && desc != "" {
			preview.Description = desc
		}
	}
	return preview
}

// extractTwitter uses vxtwitter/fxtwitter for reliable OG metadata from tweets.
func extractTwitter(targetURL, username, tweetID string, log *zap.Logger) LinkPreview {
	preview := LinkPreview{
		URL:        targetURL,
		Provider:   "twitter",
		SiteName:   "X (Twitter)",
		MediaType:  "article",
		AuthorName: "@" + username,
		VideoID:    tweetID,
		Favicon:    "https://www.google.com/s2/favicons?domain=x.com&sz=64",
	}

	// Use vxtwitter which provides reliable OG tags for scraping
	vxURL := fmt.Sprintf("https://vxtwitter.com/%s/status/%s", username, tweetID)
	vxParsed, _ := url.Parse(vxURL)
	if vxParsed != nil {
		scraped, err := fetchAndExtractPreview(vxURL, vxParsed, log)
		if err == nil {
			if scraped.Title != "" {
				preview.Title = scraped.Title
			}
			if scraped.Description != "" {
				preview.Description = scraped.Description
			}
			if scraped.Image != "" {
				preview.Image = scraped.Image
			}
		}
	}

	if preview.Title == "" {
		preview.Title = fmt.Sprintf("Post by @%s", username)
	}
	return preview
}

// extractMaps detects coordinates and address from Google/Apple Maps links.
func extractMaps(targetURL string, parsed *url.URL, log *zap.Logger) LinkPreview {
	preview := LinkPreview{
		URL:       targetURL,
		Provider:  "maps",
		SiteName:  "Maps",
		MediaType: "map",
		Favicon:   "https://www.google.com/s2/favicons?domain=maps.google.com&sz=64",
	}

	if strings.Contains(targetURL, "apple.com") {
		preview.SiteName = "Apple Maps"
		preview.Favicon = "https://www.google.com/s2/favicons?domain=maps.apple.com&sz=64"
	} else {
		preview.SiteName = "Google Maps"
	}

	// Try to extract coordinates
	if match := mapsCoordRegex.FindStringSubmatch(targetURL); len(match) > 2 {
		lat, lng := match[1], match[2]
		preview.Title = fmt.Sprintf("📍 %s, %s", lat, lng)
		preview.Description = "Shared location"
		// Static map thumbnail
		preview.Image = fmt.Sprintf(
			"https://maps.googleapis.com/maps/api/staticmap?center=%s,%s&zoom=15&size=400x200&markers=%s,%s",
			lat, lng, lat, lng)
	}

	// Try query param for address
	if match := mapsQueryRegex.FindStringSubmatch(targetURL); len(match) > 1 {
		query, _ := url.QueryUnescape(match[1])
		if query != "" {
			preview.Title = "📍 " + query
			preview.Description = "Shared location"
		}
	}

	if preview.Title == "" {
		preview.Title = "📍 Shared Location"
		preview.Description = "Tap to open in Maps"
	}

	return preview
}

// extractTikTok fetches metadata via TikTok's public oEmbed API.
func extractTikTok(targetURL string, log *zap.Logger) LinkPreview {
	preview := LinkPreview{
		URL:       targetURL,
		Provider:  "tiktok",
		SiteName:  "TikTok",
		MediaType: "video",
		Favicon:   "https://www.google.com/s2/favicons?domain=tiktok.com&sz=64",
	}

	oembed := fetchOEmbed(fmt.Sprintf("https://www.tiktok.com/oembed?url=%s",
		url.QueryEscape(targetURL)), log)
	if oembed != nil {
		if t, ok := oembed["title"].(string); ok && t != "" {
			preview.Title = t
		}
		if a, ok := oembed["author_name"].(string); ok && a != "" {
			preview.AuthorName = a
		}
		if img, ok := oembed["thumbnail_url"].(string); ok && img != "" {
			preview.Image = img
		}
	}
	return preview
}

// extractInstagram fetches metadata via Meta's public oEmbed (may require token).
// Falls back to OG-tag scraping if oEmbed fails.
func extractInstagram(targetURL string, log *zap.Logger) LinkPreview {
	preview := LinkPreview{
		URL:       targetURL,
		Provider:  "instagram",
		SiteName:  "Instagram",
		MediaType: "image",
		Favicon:   "https://www.google.com/s2/favicons?domain=instagram.com&sz=64",
	}

	// Instagram's oEmbed works for public posts
	oembed := fetchOEmbed(fmt.Sprintf("https://api.instagram.com/oembed?url=%s&omitscript=true",
		url.QueryEscape(targetURL)), log)
	if oembed != nil {
		if t, ok := oembed["title"].(string); ok && t != "" {
			preview.Title = t
		}
		if a, ok := oembed["author_name"].(string); ok && a != "" {
			preview.AuthorName = a
			if preview.Title == "" {
				preview.Title = fmt.Sprintf("Post by %s", a)
			}
		}
		if img, ok := oembed["thumbnail_url"].(string); ok && img != "" {
			preview.Image = img
		}
	}

	// Fallback: scrape OG tags
	if preview.Title == "" {
		parsed, _ := url.Parse(targetURL)
		if parsed != nil {
			scraped, err := fetchAndExtractPreview(targetURL, parsed, log)
			if err == nil {
				if scraped.Title != "" {
					preview.Title = scraped.Title
				}
				if scraped.Description != "" {
					preview.Description = scraped.Description
				}
				if scraped.Image != "" {
					preview.Image = scraped.Image
				}
			}
		}
	}

	return preview
}

// ── Shared helpers ───────────────────────────────────────────────────────

// fetchOEmbed calls an oEmbed endpoint and returns the parsed JSON map.
func fetchOEmbed(oembedURL string, log *zap.Logger) map[string]interface{} {
	client := &http.Client{Timeout: 4 * time.Second}
	req, err := http.NewRequestWithContext(context.Background(), "GET", oembedURL, nil)
	if err != nil {
		return nil
	}
	req.Header.Set("User-Agent", "Mozilla/5.0 (compatible; GoChatBot/1.0; +https://gochat.app/bot)")
	req.Header.Set("Accept", "application/json")

	resp, err := client.Do(req)
	if err != nil {
		if log != nil {
			log.Debug("oEmbed fetch failed", zap.String("url", oembedURL), zap.Error(err))
		}
		return nil
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return nil
	}

	body, err := io.ReadAll(io.LimitReader(resp.Body, 64*1024))
	if err != nil {
		return nil
	}

	var result map[string]interface{}
	if err := json.Unmarshal(body, &result); err != nil {
		return nil
	}
	return result
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

