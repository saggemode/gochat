package middleware

import (
	"fmt"
	"net/http"
	"strings"

	"github.com/gin-gonic/gin"
)

const (
	// CurrentAPIVersion is the active production API version string.
	CurrentAPIVersion = "1.0"

	// HeaderAPIVersion is the header identifying the API version of the response.
	HeaderAPIVersion = "X-API-Version"

	// HeaderSupportedVer lists all supported API versions.
	HeaderSupportedVer = "X-Supported-Versions"

	// HeaderDeprecation indicates if the requested endpoint is deprecated.
	HeaderDeprecation = "Deprecation"

	// HeaderWarning carries standard RFC 7234 warning text for clients.
	HeaderWarning = "Warning"
)

// SupportedVersions contains the list of currently supported API versions.
var SupportedVersions = []string{"v1"}

// VersioningMiddleware provides standard versioning headers and validates client-requested versions.
func VersioningMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		// Check client-requested version header if explicitly provided
		reqVersion := strings.TrimSpace(c.GetHeader("Accept-Version"))
		if reqVersion == "" {
			reqVersion = strings.TrimSpace(c.GetHeader(HeaderAPIVersion))
		}

		if reqVersion != "" {
			valid := false
			for _, v := range SupportedVersions {
				if strings.EqualFold(reqVersion, v) || reqVersion == "1.0" || reqVersion == "1" {
					valid = true
					break
				}
			}
			if !valid {
				c.JSON(http.StatusBadRequest, gin.H{
					"error":              fmt.Sprintf("Unsupported API version '%s'", reqVersion),
					"supported_versions": SupportedVersions,
					"current_version":    CurrentAPIVersion,
				})
				c.Abort()
				return
			}
		}

		// Standard versioning headers on all responses
		c.Header(HeaderAPIVersion, CurrentAPIVersion)
		c.Header(HeaderSupportedVer, strings.Join(SupportedVersions, ", "))

		c.Next()
	}
}

// FallbackRewriteHandler is mounted on r.NoRoute to transparently re-route
// unversioned legacy /api/... requests to /api/v1/... without breaking existing clients.
func FallbackRewriteHandler(engine *gin.Engine) gin.HandlerFunc {
	return func(c *gin.Context) {
		path := c.Request.URL.Path
		if strings.HasPrefix(path, "/api/") && !strings.HasPrefix(path, "/api/v1/") && !strings.HasPrefix(path, "/api/v2/") {
			rewritten := "/api/v1" + strings.TrimPrefix(path, "/api")
			c.Request.URL.Path = rewritten
			c.Header(HeaderDeprecation, "true")
			c.Header(HeaderWarning, fmt.Sprintf(`299 - "Unversioned API endpoint is deprecated. Please migrate to %s"`, rewritten))
			engine.HandleContext(c)
			return
		}
		c.JSON(http.StatusNotFound, gin.H{"error": "Endpoint not found"})
	}
}
