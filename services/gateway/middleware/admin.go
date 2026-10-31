package middleware

import (
	"net/http"
	"strings"

	"github.com/gin-gonic/gin"
)

// AdminMiddleware gates the /api/v1/admin endpoints. It must run AFTER
// AuthMiddleware (which validates the JWT and sets "email" in the context).
// Authorisation is an email allowlist supplied via the ADMIN_EMAILS env var
// (see config.AdminEmails); there is no role column in the schema yet, so this
// is the minimal, auditable gate. When the allowlist is empty, every request
// is denied — the admin API is locked until an operator opts specific accounts
// in.
//
// Upgrade path: once core.users gains an is_admin/role column, swap the
// allowlist check for a lookup without touching the routes or handlers.
func AdminMiddleware(adminEmails []string) gin.HandlerFunc {
	allow := make(map[string]struct{}, len(adminEmails))
	for _, e := range adminEmails {
		allow[strings.ToLower(strings.TrimSpace(e))] = struct{}{}
	}

	return func(c *gin.Context) {
		email, _ := c.Get("email")
		emailStr, _ := email.(string)
		emailStr = strings.ToLower(strings.TrimSpace(emailStr))

		if emailStr == "" {
			// AuthMiddleware should have populated this; defend anyway.
			c.JSON(http.StatusUnauthorized, gin.H{"error": "authentication required"})
			c.Abort()
			return
		}

		if _, ok := allow[emailStr]; !ok {
			c.JSON(http.StatusForbidden, gin.H{"error": "admin access required"})
			c.Abort()
			return
		}

		c.Set("is_admin", true)
		c.Next()
	}
}
