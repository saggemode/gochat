package middleware

import (
	"net/http"
	"strings"

	"github.com/gin-gonic/gin"
	"google.golang.org/grpc/metadata"

	authpb "gochat/gen/auth"
)

// AuthMiddleware intercepts requests, validates JWT via Auth gRPC, and stores user details in context.
func AuthMiddleware(authClient authpb.AuthServiceClient) gin.HandlerFunc {
	return func(c *gin.Context) {
		token := ""

		// Check Authorization Header
		authHeader := c.GetHeader("Authorization")
		if authHeader != "" {
			parts := strings.Split(authHeader, " ")
			if len(parts) == 2 && strings.ToLower(parts[0]) == "bearer" {
				token = parts[1]
			}
		}

		// Fallback to query parameter (useful for WebSocket connection initialisation)
		if token == "" {
			token = c.Query("token")
		}

		// Fallback to HttpOnly cookie (browser clients)
		if token == "" {
			if cookieToken, err := c.Cookie("gochat_access_token"); err == nil && cookieToken != "" {
				token = cookieToken
			}
		}

		if token == "" {
			c.JSON(http.StatusUnauthorized, gin.H{"error": "Authorization token required"})
			c.Abort()
			return
		}

		// Validate token via Auth Service gRPC
		ctx := metadata.AppendToOutgoingContext(c.Request.Context(), "authorization", "Bearer "+token)
		resp, err := authClient.ValidateToken(ctx, &authpb.ValidateTokenRequest{Token: token})
		if err != nil || resp == nil || !resp.Valid {
			c.JSON(http.StatusUnauthorized, gin.H{"error": "Invalid or expired token"})
			c.Abort()
			return
		}

		// Store user details in context
		c.Set("user_id", resp.UserId)
		c.Set("email", resp.Email)

		c.Next()
	}
}
