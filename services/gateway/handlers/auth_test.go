package handlers

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/gin-gonic/gin"
	"go.uber.org/zap"
	"google.golang.org/grpc"

	authpb "gochat/gen/auth"
)

func TestAuthHandler_RegisterForwardsPhone(t *testing.T) {
	gin.SetMode(gin.TestMode)

	var got *authpb.RegisterRequest
	client := &stubAuthServiceClient{registerFn: func(_ context.Context, req *authpb.RegisterRequest, _ ...grpc.CallOption) (*authpb.RegisterResponse, error) {
		got = req
		return &authpb.RegisterResponse{User: &authpb.User{Id: "user-1"}}, nil
	}}

	handler := NewAuthHandler(client, zap.NewNop())
	r := gin.New()
	r.POST("/auth/register", handler.Register)

	body := `{"email":"user@example.com","password":"secret123","display_name":"Test User","phone":"+1234567890"}`
	req := httptest.NewRequest(http.MethodPost, "/auth/register", strings.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()

	r.ServeHTTP(w, req)

	if w.Code != http.StatusCreated {
		t.Fatalf("status = %d, want %d", w.Code, http.StatusCreated)
	}
	if got == nil {
		t.Fatal("expected register request to be sent to auth service")
	}
	if got.Phone != "+1234567890" {
		t.Fatalf("phone = %q, want %q", got.Phone, "+1234567890")
	}
}

type stubAuthServiceClient struct {
	registerFn func(context.Context, *authpb.RegisterRequest, ...grpc.CallOption) (*authpb.RegisterResponse, error)
}

func (s *stubAuthServiceClient) ValidateToken(context.Context, *authpb.ValidateTokenRequest, ...grpc.CallOption) (*authpb.ValidateTokenResponse, error) {
	return &authpb.ValidateTokenResponse{Valid: true}, nil
}

func (s *stubAuthServiceClient) Register(ctx context.Context, req *authpb.RegisterRequest, opts ...grpc.CallOption) (*authpb.RegisterResponse, error) {
	if s.registerFn != nil {
		return s.registerFn(ctx, req, opts...)
	}
	return &authpb.RegisterResponse{}, nil
}

func (s *stubAuthServiceClient) Login(context.Context, *authpb.LoginRequest, ...grpc.CallOption) (*authpb.LoginResponse, error) {
	panic("not implemented")
}
func (s *stubAuthServiceClient) RefreshToken(context.Context, *authpb.RefreshTokenRequest, ...grpc.CallOption) (*authpb.RefreshTokenResponse, error) {
	panic("not implemented")
}
func (s *stubAuthServiceClient) GetUser(context.Context, *authpb.GetUserRequest, ...grpc.CallOption) (*authpb.GetUserResponse, error) {
	panic("not implemented")
}
func (s *stubAuthServiceClient) UpdateUser(context.Context, *authpb.UpdateUserRequest, ...grpc.CallOption) (*authpb.UpdateUserResponse, error) {
	panic("not implemented")
}
func (s *stubAuthServiceClient) DeleteUser(context.Context, *authpb.DeleteUserRequest, ...grpc.CallOption) (*authpb.DeleteUserResponse, error) {
	panic("not implemented")
}
func (s *stubAuthServiceClient) Logout(context.Context, *authpb.LogoutRequest, ...grpc.CallOption) (*authpb.LogoutResponse, error) {
	panic("not implemented")
}
func (s *stubAuthServiceClient) GetUsers(context.Context, *authpb.GetUsersRequest, ...grpc.CallOption) (*authpb.GetUsersResponse, error) {
	panic("not implemented")
}
func (s *stubAuthServiceClient) SetPresence(context.Context, *authpb.SetPresenceRequest, ...grpc.CallOption) (*authpb.SetPresenceResponse, error) {
	panic("not implemented")
}
func (s *stubAuthServiceClient) SetTwoStepPIN(context.Context, *authpb.SetTwoStepPINRequest, ...grpc.CallOption) (*authpb.SetTwoStepPINResponse, error) {
	panic("not implemented")
}
func (s *stubAuthServiceClient) VerifyTwoStepPIN(context.Context, *authpb.VerifyTwoStepPINRequest, ...grpc.CallOption) (*authpb.VerifyTwoStepPINResponse, error) {
	panic("not implemented")
}
func (s *stubAuthServiceClient) UploadE2EEKeys(context.Context, *authpb.UploadE2EEKeysRequest, ...grpc.CallOption) (*authpb.UploadE2EEKeysResponse, error) {
	panic("not implemented")
}
func (s *stubAuthServiceClient) GetE2EEKeys(context.Context, *authpb.GetE2EEKeysRequest, ...grpc.CallOption) (*authpb.GetE2EEKeysResponse, error) {
	panic("not implemented")
}
func (s *stubAuthServiceClient) BlockUser(context.Context, *authpb.BlockUserRequest, ...grpc.CallOption) (*authpb.BlockUserResponse, error) {
	panic("not implemented")
}
func (s *stubAuthServiceClient) UnblockUser(context.Context, *authpb.UnblockUserRequest, ...grpc.CallOption) (*authpb.UnblockUserResponse, error) {
	panic("not implemented")
}
func (s *stubAuthServiceClient) GetBlockedUsers(context.Context, *authpb.GetBlockedUsersRequest, ...grpc.CallOption) (*authpb.GetBlockedUsersResponse, error) {
	panic("not implemented")
}
func (s *stubAuthServiceClient) RegisterPhone(context.Context, *authpb.RegisterPhoneRequest, ...grpc.CallOption) (*authpb.RegisterPhoneResponse, error) {
	panic("not implemented")
}
func (s *stubAuthServiceClient) VerifyPhoneOTP(context.Context, *authpb.VerifyPhoneOTPRequest, ...grpc.CallOption) (*authpb.VerifyPhoneOTPResponse, error) {
	panic("not implemented")
}
func (s *stubAuthServiceClient) SubscribePush(context.Context, *authpb.SubscribePushRequest, ...grpc.CallOption) (*authpb.SubscribePushResponse, error) {
	panic("not implemented")
}
