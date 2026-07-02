package middleware

import (
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/gin-gonic/gin"
)

func TestAuthMiddleware_MissingToken(t *testing.T) {
	gin.SetMode(gin.TestMode)

	r := gin.New()
	r.Use(AuthMiddleware(nil))
	r.GET("/test", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{"ok": true})
	})

	req := httptest.NewRequest(http.MethodGet, "/test", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusUnauthorized {
		t.Errorf("status = %d, want %d", w.Code, http.StatusUnauthorized)
	}
}

func TestAuthMiddleware_InvalidScheme(t *testing.T) {
	gin.SetMode(gin.TestMode)

	r := gin.New()
	r.Use(AuthMiddleware(nil))
	r.GET("/test", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{"ok": true})
	})

	req := httptest.NewRequest(http.MethodGet, "/test", nil)
	req.Header.Set("Authorization", "Basic sometoken")
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusUnauthorized {
		t.Errorf("status = %d, want %d", w.Code, http.StatusUnauthorized)
	}
}

func TestAuthMiddleware_BearerTokenExtracted(t *testing.T) {
	gin.SetMode(gin.TestMode)

	var extractedToken string

	r := gin.New()
	r.Use(AuthMiddleware(&mockAuthClient{valid: true, userID: "user-1", email: "test@test.com"}))
	r.GET("/test", func(c *gin.Context) {
		extractedToken = c.GetString("user_id")
		c.JSON(http.StatusOK, gin.H{"ok": true})
	})

	req := httptest.NewRequest(http.MethodGet, "/test", nil)
	req.Header.Set("Authorization", "Bearer my-jwt-token")
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Errorf("status = %d, want %d", w.Code, http.StatusOK)
	}
	if extractedToken != "user-1" {
		t.Errorf("user_id = %q, want %q", extractedToken, "user-1")
	}
}

func TestAuthMiddleware_QueryTokenFallback(t *testing.T) {
	gin.SetMode(gin.TestMode)

	r := gin.New()
	r.Use(AuthMiddleware(&mockAuthClient{valid: true, userID: "user-2", email: "q@test.com"}))
	r.GET("/test", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{"ok": true})
	})

	req := httptest.NewRequest(http.MethodGet, "/test?token=query-jwt-token", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Errorf("status = %d, want %d", w.Code, http.StatusOK)
	}
}

func TestAuthMiddleware_InvalidToken(t *testing.T) {
	gin.SetMode(gin.TestMode)

	r := gin.New()
	r.Use(AuthMiddleware(&mockAuthClient{valid: false}))
	r.GET("/test", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{"ok": true})
	})

	req := httptest.NewRequest(http.MethodGet, "/test", nil)
	req.Header.Set("Authorization", "Bearer bad-token")
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusUnauthorized {
		t.Errorf("status = %d, want %d", w.Code, http.StatusUnauthorized)
	}
}

type mockAuthClient struct {
	valid  bool
	userID string
	email  string
}

func (m *mockAuthClient) ValidateToken(_ context.Context, req *authpb.ValidateTokenRequest) (*authpb.ValidateTokenResponse, error) {
	return &authpb.ValidateTokenResponse{
		Valid:  m.valid,
		UserId: m.userID,
		Email:  m.email,
	}, nil
}
