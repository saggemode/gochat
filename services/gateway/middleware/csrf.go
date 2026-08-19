package middleware

import (
	"crypto/subtle"
	"net/http"
	"strings"

	"github.com/gin-gonic/gin"
)

// CsrfMiddleware provides basic CSRF protection for cookie-authenticated requests.
//
// Design:
// - If the request uses Bearer auth, we skip CSRF checks (non-cookie auth).
// - If the request has the gochat_access_token cookie (cookie auth) and is a
//   state-changing method, require X-CSRF-Token to match the gochat_csrf cookie.
//
// NOTE: This assumes same-site cookies (SameSite=Lax/Strict) and is intended for
// browser clients. For APIs used cross-site, consider stronger CSRF controls.
func CsrfMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		method := strings.ToUpper(c.Request.Method)
		switch method {
		case http.MethodGet, http.MethodHead, http.MethodOptions:
			c.Next()
			return
		}

		// If Bearer auth is used, skip CSRF validation.
		authHeader := strings.TrimSpace(c.GetHeader("Authorization"))
		if strings.HasPrefix(strings.ToLower(authHeader), "bearer ") {
			c.Next()
			return
		}

		// Only enforce if cookie auth is in play.
		accessCookie, err := c.Cookie("gochat_access_token")
		if err != nil || accessCookie == "" {
			c.Next()
			return
		}

		csrfCookie, err := c.Cookie("gochat_csrf")
		if err != nil || csrfCookie == "" {
			c.JSON(http.StatusForbidden, gin.H{"error": "CSRF token missing"})
			c.Abort()
			return
		}

		csrfHeader := c.GetHeader("X-CSRF-Token")
		if csrfHeader == "" || subtle.ConstantTimeCompare([]byte(csrfHeader), []byte(csrfCookie)) != 1 {
			c.JSON(http.StatusForbidden, gin.H{"error": "CSRF token invalid"})
			c.Abort()
			return
		}

		c.Next()
	}
}

