package middleware

import (
	"os"
	"strings"

	"github.com/gin-gonic/gin"
)

// GeoMiddleware extracts the user's ISO 3166-1 alpha-2 country code from
// reverse-proxy headers and stores it in the Gin context as "country_code".
//
// Detection order:
//  1. CF-IPCountry   (Cloudflare)
//  2. X-Country-Code (generic reverse proxy / Nginx GeoIP2)
//  3. DEFAULT_COUNTRY_CODE env var (local dev fallback)
//  4. "unknown"
func GeoMiddleware() gin.HandlerFunc {
	fallback := os.Getenv("DEFAULT_COUNTRY_CODE")
	if fallback == "" {
		fallback = "unknown"
	}

	return func(c *gin.Context) {
		cc := ""

		// 1. Cloudflare
		if v := c.GetHeader("CF-IPCountry"); v != "" {
			cc = strings.ToUpper(strings.TrimSpace(v))
		}

		// 2. Generic reverse proxy header
		if cc == "" {
			if v := c.GetHeader("X-Country-Code"); v != "" {
				cc = strings.ToUpper(strings.TrimSpace(v))
			}
		}

		// 3. Fallback
		if cc == "" {
			cc = fallback
		}

		c.Set("country_code", cc)
		c.Next()
	}
}
