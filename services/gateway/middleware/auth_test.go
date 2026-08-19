package middleware

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/gin-gonic/gin"
	"google.golang.org/grpc"

	authpb "gochat/gen/auth"
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

// ── Mock AuthServiceClient ───────────────────────────────────────────────────

type mockAuthClient struct {
	valid  bool
	userID string
	email  string
}

func (m *mockAuthClient) ValidateToken(_ context.Context, req *authpb.ValidateTokenRequest, _ ...grpc.CallOption) (*authpb.ValidateTokenResponse, error) {
	return &authpb.ValidateTokenResponse{
		Valid:  m.valid,
		UserId: m.userID,
		Email:  m.email,
	}, nil
}

// Stub methods to satisfy authpb.AuthServiceClient interface — only ValidateToken is exercised by middleware.

func (m *mockAuthClient) Register(_ context.Context, _ *authpb.RegisterRequest, _ ...grpc.CallOption) (*authpb.RegisterResponse, error) {
	panic("not used in middleware tests")
}
func (m *mockAuthClient) Login(_ context.Context, _ *authpb.LoginRequest, _ ...grpc.CallOption) (*authpb.LoginResponse, error) {
	panic("not used in middleware tests")
}
func (m *mockAuthClient) RefreshToken(_ context.Context, _ *authpb.RefreshTokenRequest, _ ...grpc.CallOption) (*authpb.RefreshTokenResponse, error) {
	panic("not used in middleware tests")
}
func (m *mockAuthClient) GetUser(_ context.Context, _ *authpb.GetUserRequest, _ ...grpc.CallOption) (*authpb.GetUserResponse, error) {
	panic("not used in middleware tests")
}
func (m *mockAuthClient) UpdateUser(_ context.Context, _ *authpb.UpdateUserRequest, _ ...grpc.CallOption) (*authpb.UpdateUserResponse, error) {
	panic("not used in middleware tests")
}
func (m *mockAuthClient) DeleteUser(_ context.Context, _ *authpb.DeleteUserRequest, _ ...grpc.CallOption) (*authpb.DeleteUserResponse, error) {
	panic("not used in middleware tests")
}
func (m *mockAuthClient) Logout(_ context.Context, _ *authpb.LogoutRequest, _ ...grpc.CallOption) (*authpb.LogoutResponse, error) {
	panic("not used in middleware tests")
}
func (m *mockAuthClient) GetUsers(_ context.Context, _ *authpb.GetUsersRequest, _ ...grpc.CallOption) (*authpb.GetUsersResponse, error) {
	panic("not used in middleware tests")
}
func (m *mockAuthClient) SetPresence(_ context.Context, _ *authpb.SetPresenceRequest, _ ...grpc.CallOption) (*authpb.SetPresenceResponse, error) {
	panic("not used in middleware tests")
}
func (m *mockAuthClient) SetTwoStepPIN(_ context.Context, _ *authpb.SetTwoStepPINRequest, _ ...grpc.CallOption) (*authpb.SetTwoStepPINResponse, error) {
	panic("not used in middleware tests")
}
func (m *mockAuthClient) VerifyTwoStepPIN(_ context.Context, _ *authpb.VerifyTwoStepPINRequest, _ ...grpc.CallOption) (*authpb.VerifyTwoStepPINResponse, error) {
	panic("not used in middleware tests")
}
func (m *mockAuthClient) UploadE2EEKeys(_ context.Context, _ *authpb.UploadE2EEKeysRequest, _ ...grpc.CallOption) (*authpb.UploadE2EEKeysResponse, error) {
	panic("not used in middleware tests")
}
func (m *mockAuthClient) GetE2EEKeys(_ context.Context, _ *authpb.GetE2EEKeysRequest, _ ...grpc.CallOption) (*authpb.GetE2EEKeysResponse, error) {
	panic("not used in middleware tests")
}
func (m *mockAuthClient) BlockUser(_ context.Context, _ *authpb.BlockUserRequest, _ ...grpc.CallOption) (*authpb.BlockUserResponse, error) {
	panic("not used in middleware tests")
}
func (m *mockAuthClient) UnblockUser(_ context.Context, _ *authpb.UnblockUserRequest, _ ...grpc.CallOption) (*authpb.UnblockUserResponse, error) {
	panic("not used in middleware tests")
}
func (m *mockAuthClient) GetBlockedUsers(_ context.Context, _ *authpb.GetBlockedUsersRequest, _ ...grpc.CallOption) (*authpb.GetBlockedUsersResponse, error) {
	panic("not used in middleware tests")
}
func (m *mockAuthClient) RegisterPhone(_ context.Context, _ *authpb.RegisterPhoneRequest, _ ...grpc.CallOption) (*authpb.RegisterPhoneResponse, error) {
	panic("not used in middleware tests")
}
func (m *mockAuthClient) VerifyPhoneOTP(_ context.Context, _ *authpb.VerifyPhoneOTPRequest, _ ...grpc.CallOption) (*authpb.VerifyPhoneOTPResponse, error) {
	panic("not used in middleware tests")
}
func (m *mockAuthClient) SubscribePush(_ context.Context, _ *authpb.SubscribePushRequest, _ ...grpc.CallOption) (*authpb.SubscribePushResponse, error) {
	panic("not used in middleware tests")
}
