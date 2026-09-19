package middleware

import (
	"crypto/sha256"
	"fmt"
	"net/http"
	"strings"

	"github.com/gin-gonic/gin"
	"github.com/redis/go-redis/v9"
	"google.golang.org/grpc/metadata"

	authpb "gochat/gen/auth"
)

// AuthMiddleware intercepts requests, validates JWT via Auth gRPC, checks Redis token revocation, and stores user details in context.
func AuthMiddleware(authClient authpb.AuthServiceClient, rdb ...*redis.Client) gin.HandlerFunc {
	var redisClient *redis.Client
	if len(rdb) > 0 {
		redisClient = rdb[0]
	}

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

		// Check if token has been revoked via remote session logout
		if redisClient != nil {
			h := sha256.Sum256([]byte(token))
			tokenHash := fmt.Sprintf("%x", h[:])
			if revoked, err := redisClient.Get(c.Request.Context(), "jwt:revoked:"+tokenHash).Result(); err == nil && revoked == "revoked" {
				c.JSON(http.StatusUnauthorized, gin.H{"error": "Session revoked. Please log in again."})
				c.Abort()
				return
			}
		}

		// Validate token via Auth Service gRPC
		if authClient == nil {
			c.JSON(http.StatusUnauthorized, gin.H{"error": "Auth client unavailable"})
			c.Abort()
			return
		}

		ctx := metadata.AppendToOutgoingContext(c.Request.Context(), "authorization", "Bearer "+token)
		resp, err := authClient.ValidateToken(ctx, &authpb.ValidateTokenRequest{Token: token})
		if err != nil || resp == nil || !resp.Valid {
			c.JSON(http.StatusUnauthorized, gin.H{"error": "Invalid or expired token"})
			c.Abort()
			return
		}

		// Check if device has been revoked via remote session logout
		if redisClient != nil {
			deviceID := c.GetHeader("X-Device-Id")
			if deviceID != "" {
				if devRevoked, err := redisClient.Get(c.Request.Context(), "device:revoked:"+resp.UserId+":"+deviceID).Result(); err == nil && devRevoked == "revoked" {
					c.JSON(http.StatusUnauthorized, gin.H{"error": "Device session has been logged out remotely. Please log in again."})
					c.Abort()
					return
				}
			}
		}

		// Store user details in context
		c.Set("user_id", resp.UserId)
		c.Set("email", resp.Email)

		c.Next()
	}
}
