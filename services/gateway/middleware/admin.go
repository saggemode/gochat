package middleware

import (
	"net/http"
	"strings"

	"github.com/gin-gonic/gin"
	"go.uber.org/zap"

	authzpb "gochat/gen/authz"
)

// AdminMiddleware gates the /api/v1/admin endpoints. It must run AFTER
// AuthMiddleware (which validates the JWT and sets "user_id"/"email").
//
// Authorization is RBAC via the authz service: the user must hold the
// `admin:access` permission (granted by the admin/super_admin/support/finance/
// moderator roles — see migrations/authz/002_admin_rbac.sql). The ADMIN_EMAILS
// allowlist is a BREAK-GLASS bootstrap: it lets listed accounts in when authz
// denies (e.g. roles not yet assigned) or is unreachable, and those requests
// are flagged is_bootstrap_admin + logged. Once roles are assigned, authz is
// the source of truth.
func AdminMiddleware(authz authzpb.AuthzServiceClient, adminEmails []string, log *zap.Logger) gin.HandlerFunc {
	allow := make(map[string]struct{}, len(adminEmails))
	for _, e := range adminEmails {
		allow[strings.ToLower(strings.TrimSpace(e))] = struct{}{}
	}

	return func(c *gin.Context) {
		userID := c.GetString("user_id")
		email := strings.ToLower(strings.TrimSpace(c.GetString("email")))
		if userID == "" {
			c.JSON(http.StatusUnauthorized, gin.H{"error": "authentication required"})
			c.Abort()
			return
		}

		// Primary: RBAC check via authz.
		if authz != nil {
			resp, err := authz.Authorize(c.Request.Context(), &authzpb.AuthorizeRequest{
				UserId: userID,
				Action: "admin:access",
			})
			if err == nil && resp.GetAllowed() {
				c.Set("is_admin", true)
				c.Next()
				return
			}
			if err != nil {
				log.Warn("admin authz check failed; falling back to bootstrap allowlist", zap.Error(err))
			}
		}

		// Break-glass: ADMIN_EMAILS bootstrap.
		if _, ok := allow[email]; ok {
			c.Set("is_admin", true)
			c.Set("is_bootstrap_admin", true)
			log.Warn("admin access granted via ADMIN_EMAILS bootstrap (not RBAC)",
				zap.String("email", email))
			c.Next()
			return
		}

		c.JSON(http.StatusForbidden, gin.H{"error": "admin access required"})
		c.Abort()
	}
}

// RequirePermission gates a single admin route on a specific authz permission
// (e.g. "admin:users:write"). It runs after AdminMiddleware. Break-glass
// bootstrap admins (ADMIN_EMAILS) are allowed through, since they are the
// platform owners and authz may not yet know them.
func RequirePermission(authz authzpb.AuthzServiceClient, action string, log *zap.Logger) gin.HandlerFunc {
	return func(c *gin.Context) {
		if authz != nil {
			userID := c.GetString("user_id")
			resp, err := authz.Authorize(c.Request.Context(), &authzpb.AuthorizeRequest{
				UserId: userID,
				Action: action,
			})
			if err == nil {
				if resp.GetAllowed() {
					c.Next()
					return
				}
				// Explicit deny — unless this is a break-glass bootstrap admin.
				if c.GetBool("is_bootstrap_admin") {
					c.Next()
					return
				}
				c.JSON(http.StatusForbidden, gin.H{"error": "missing permission: " + action})
				c.Abort()
				return
			}
			log.Warn("permission check failed; allowing bootstrap admin only", zap.String("action", action), zap.Error(err))
		}

		// authz unreachable: only break-glass bootstrap admins may proceed.
		if c.GetBool("is_bootstrap_admin") {
			c.Next()
			return
		}
		c.JSON(http.StatusForbidden, gin.H{"error": "missing permission: " + action})
		c.Abort()
	}
}
