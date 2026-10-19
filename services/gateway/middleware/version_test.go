package middleware

import (
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/gin-gonic/gin"
)

func TestVersioningMiddleware_Headers(t *testing.T) {
	gin.SetMode(gin.TestMode)

	r := gin.New()
	r.Use(VersioningMiddleware())
	r.GET("/api/v1/ping", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{"pong": true})
	})

	req := httptest.NewRequest(http.MethodGet, "/api/v1/ping", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("status = %d, want %d", w.Code, http.StatusOK)
	}

	if val := w.Header().Get(HeaderAPIVersion); val != CurrentAPIVersion {
		t.Errorf("X-API-Version = %q, want %q", val, CurrentAPIVersion)
	}

	if val := w.Header().Get(HeaderSupportedVer); val != "v1" {
		t.Errorf("X-Supported-Versions = %q, want 'v1'", val)
	}
}

func TestVersioningMiddleware_LegacyRewrite(t *testing.T) {
	gin.SetMode(gin.TestMode)

	r := gin.New()
	r.Use(VersioningMiddleware())
	r.NoRoute(FallbackRewriteHandler(r))

	r.GET("/api/v1/chat/messages", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{"messages": []string{}})
	})

	// Make request to legacy unversioned endpoint /api/chat/messages
	req := httptest.NewRequest(http.MethodGet, "/api/chat/messages", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("expected 200 OK after internal rewrite, got %d", w.Code)
	}

	if dep := w.Header().Get(HeaderDeprecation); dep != "true" {
		t.Errorf("Deprecation header = %q, want 'true'", dep)
	}

	if warn := w.Header().Get(HeaderWarning); warn == "" {
		t.Errorf("Warning header expected for unversioned request")
	}
}

func TestVersioningMiddleware_UnsupportedVersion(t *testing.T) {
	gin.SetMode(gin.TestMode)

	r := gin.New()
	r.Use(VersioningMiddleware())
	r.GET("/api/v1/ping", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{"pong": true})
	})

	req := httptest.NewRequest(http.MethodGet, "/api/v1/ping", nil)
	req.Header.Set("Accept-Version", "v99")
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400 Bad Request for unsupported version", w.Code)
	}
}
