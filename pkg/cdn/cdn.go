package cdn

import (
	"crypto/md5"
	"fmt"
	"net/http"
	"strings"

	"github.com/gin-gonic/gin"
)

// CDNMiddleware adds CDN edge caching, ETag, and Cache-Control headers for static and media assets.
func CDNMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		path := c.Request.URL.Path

		// Apply edge caching headers for static assets & uploaded media
		if strings.HasPrefix(path, "/media/") || strings.HasPrefix(path, "/_next/") || strings.HasSuffix(path, ".png") || strings.HasSuffix(path, ".jpg") || strings.HasSuffix(path, ".webp") || strings.HasSuffix(path, ".svg") {
			c.Header("Cache-Control", "public, max-age=31536000, immutable")
			c.Header("Vary", "Accept-Encoding")

			// Generate ETag for client caching
			etag := fmt.Sprintf(`W/"%x"`, md5.Sum([]byte(path)))
			c.Header("ETag", etag)

			if c.GetHeader("If-None-Match") == etag {
				c.Status(http.StatusNotModified)
				c.Abort()
				return
			}
		}

		c.Next()
	}
}
