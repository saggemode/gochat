package handlers

import (
	"net/http"

	"github.com/gin-gonic/gin"
)

// getUserID retrieves the authenticated user's ID from the Gin context.
// If the key is not found or empty, it returns a 401 Unauthorized response,
// aborts the request, and returns an empty string.
func getUserID(c *gin.Context) string {
	userID := c.GetString("user_id")
	if userID == "" {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "unauthorized: missing user identification"})
		c.Abort()
	}
	return userID
}
