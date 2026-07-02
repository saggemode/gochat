package handlers

import (
	"net/http"

	"github.com/gin-gonic/gin"
	"go.uber.org/zap"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"

	authpb "gochat/gen/auth"
)

// AuthHandler wraps the Auth Service gRPC client.
type AuthHandler struct {
	client authpb.AuthServiceClient
	log    *zap.Logger
}

// NewAuthHandler constructs the AuthHandler.
func NewAuthHandler(client authpb.AuthServiceClient, log *zap.Logger) *AuthHandler {
	return &AuthHandler{client: client, log: log}
}

// Register handles user registration.
func (h *AuthHandler) Register(c *gin.Context) {
	var req struct {
		Email       string `json:"email" binding:"required,email"`
		Password    string `json:"password" binding:"required,min=6"`
		DisplayName string `json:"display_name" binding:"required"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	resp, err := h.client.Register(c.Request.Context(), &authpb.RegisterRequest{
		Email:       req.Email,
		Password:    req.Password,
		DisplayName: req.DisplayName,
	})
	if err != nil {
		h.handleGrpcError(c, err, "registration failed")
		return
	}

	c.JSON(http.StatusCreated, resp)
}

// Login handles user authentication.
func (h *AuthHandler) Login(c *gin.Context) {
	var req struct {
		Email    string `json:"email" binding:"required,email"`
		Password string `json:"password" binding:"required"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	resp, err := h.client.Login(c.Request.Context(), &authpb.LoginRequest{
		Email:    req.Email,
		Password: req.Password,
	})
	if err != nil {
		h.handleGrpcError(c, err, "login failed")
		return
	}

	c.JSON(http.StatusOK, resp)
}

// Refresh handles access token rotation using a refresh token.
func (h *AuthHandler) Refresh(c *gin.Context) {
	var req struct {
		RefreshToken string `json:"refresh_token" binding:"required"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	resp, err := h.client.RefreshToken(c.Request.Context(), &authpb.RefreshTokenRequest{
		RefreshToken: req.RefreshToken,
	})
	if err != nil {
		h.handleGrpcError(c, err, "token refresh failed")
		return
	}

	c.JSON(http.StatusOK, resp)
}

// Logout revokes the provided refresh token.
func (h *AuthHandler) Logout(c *gin.Context) {
	var req struct {
		RefreshToken string `json:"refresh_token" binding:"required"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	resp, err := h.client.Logout(c.Request.Context(), &authpb.LogoutRequest{
		RefreshToken: req.RefreshToken,
	})
	if err != nil {
		h.handleGrpcError(c, err, "logout failed")
		return
	}

	c.JSON(http.StatusOK, resp)
}

// GetUser fetches details for a specific user ID.
func (h *AuthHandler) GetUser(c *gin.Context) {
	userID := c.Param("id")
	if userID == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "user id is required"})
		return
	}

	resp, err := h.client.GetUser(c.Request.Context(), &authpb.GetUserRequest{
		UserId: userID,
	})
	if err != nil {
		h.handleGrpcError(c, err, "get user failed")
		return
	}

	c.JSON(http.StatusOK, resp.User)
}

// UpdateUser modifies details for the currently logged-in user.
func (h *AuthHandler) UpdateUser(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}

	var req struct {
		DisplayName string `json:"display_name"`
		AvatarURL   string `json:"avatar_url"`
		StatusText  string `json:"status_text"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	resp, err := h.client.UpdateUser(c.Request.Context(), &authpb.UpdateUserRequest{
		UserId:      userID,
		DisplayName: req.DisplayName,
		AvatarUrl:   req.AvatarURL,
		StatusText:  req.StatusText,
	})
	if err != nil {
		h.handleGrpcError(c, err, "update profile failed")
		return
	}

	c.JSON(http.StatusOK, resp.User)
}

// UpdatePresence modifies online presence status.
func (h *AuthHandler) UpdatePresence(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}

	var req struct {
		IsOnline bool `json:"is_online"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	resp, err := h.client.SetPresence(c.Request.Context(), &authpb.SetPresenceRequest{
		UserId:   userID,
		IsOnline: req.IsOnline,
	})
	if err != nil {
		h.handleGrpcError(c, err, "presence update failed")
		return
	}

	c.JSON(http.StatusOK, gin.H{"success": resp.Success})
}

// handleGrpcError converts gRPC error codes into standard HTTP status codes.
func (h *AuthHandler) handleGrpcError(c *gin.Context, err error, actionMsg string) {
	st, ok := status.FromError(err)
	if !ok {
		h.log.Error(actionMsg, zap.Error(err))
		c.JSON(http.StatusInternalServerError, gin.H{"error": "Internal server error"})
		return
	}

	h.log.Warn(actionMsg+" with gRPC status", zap.String("code", st.Code().String()), zap.String("msg", st.Message()))

	switch st.Code() {
	case codes.InvalidArgument:
		c.JSON(http.StatusBadRequest, gin.H{"error": st.Message()})
	case codes.Unauthenticated:
		c.JSON(http.StatusUnauthorized, gin.H{"error": st.Message()})
	case codes.NotFound:
		c.JSON(http.StatusNotFound, gin.H{"error": st.Message()})
	case codes.AlreadyExists:
		c.JSON(http.StatusConflict, gin.H{"error": st.Message()})
	case codes.PermissionDenied:
		c.JSON(http.StatusForbidden, gin.H{"error": st.Message()})
	default:
		c.JSON(http.StatusInternalServerError, gin.H{"error": st.Message()})
	}
}

// SetTwoStepPIN sets or updates a user's 2FA PIN.
func (h *AuthHandler) SetTwoStepPIN(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}

	var req struct {
		Pin string `json:"pin"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	resp, err := h.client.SetTwoStepPIN(c.Request.Context(), &authpb.SetTwoStepPINRequest{
		UserId: userID,
		Pin:    req.Pin,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to set Two-Step PIN")
		return
	}

	c.JSON(http.StatusOK, gin.H{"success": resp.Success})
}

// VerifyTwoStepPIN verifies a user's 2FA PIN.
func (h *AuthHandler) VerifyTwoStepPIN(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}

	var req struct {
		Pin string `json:"pin" binding:"required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	resp, err := h.client.VerifyTwoStepPIN(c.Request.Context(), &authpb.VerifyTwoStepPINRequest{
		UserId: userID,
		Pin:    req.Pin,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to verify Two-Step PIN")
		return
	}

	c.JSON(http.StatusOK, gin.H{"valid": resp.Valid})
}

// UploadE2EEKeys registers key bundles for end-to-end encryption.
func (h *AuthHandler) UploadE2EEKeys(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}

	var req struct {
		PrekeyIdentity  string `json:"prekey_identity" binding:"required"`
		PrekeySigned    string `json:"prekey_signed" binding:"required"`
		PrekeySignature string `json:"prekey_signature" binding:"required"`
		OneTimeKeys     []struct {
			KeyId     int32  `json:"key_id"`
			PublicKey string `json:"public_key"`
		} `json:"one_time_keys"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	pbKeys := make([]*authpb.EphemeralPreKey, len(req.OneTimeKeys))
	for i, k := range req.OneTimeKeys {
		pbKeys[i] = &authpb.EphemeralPreKey{
			KeyId:     k.KeyId,
			PublicKey: k.PublicKey,
		}
	}

	resp, err := h.client.UploadE2EEKeys(c.Request.Context(), &authpb.UploadE2EEKeysRequest{
		UserId:          userID,
		PrekeyIdentity:  req.PrekeyIdentity,
		PrekeySigned:    req.PrekeySigned,
		PrekeySignature: req.PrekeySignature,
		OneTimeKeys:     pbKeys,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to upload E2EE key bundle")
		return
	}

	c.JSON(http.StatusOK, gin.H{"success": resp.Success})
}

// GetE2EEKeys retrieves and consumes a prekey bundle for a user.
func (h *AuthHandler) GetE2EEKeys(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	targetID := c.Param("user_id")

	resp, err := h.client.GetE2EEKeys(c.Request.Context(), &authpb.GetE2EEKeysRequest{
		RequesterId:  userID,
		TargetUserId: targetID,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to get E2EE key bundle")
		return
	}

	c.JSON(http.StatusOK, resp)
}

// BlockUser blocks another user.
func (h *AuthHandler) BlockUser(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	targetID := c.Param("user_id")

	resp, err := h.client.BlockUser(c.Request.Context(), &authpb.BlockUserRequest{
		UserId:       userID,
		TargetUserId: targetID,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to block user")
		return
	}

	c.JSON(http.StatusOK, gin.H{"success": resp.Success})
}

// UnblockUser unblocks a user.
func (h *AuthHandler) UnblockUser(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	targetID := c.Param("user_id")

	resp, err := h.client.UnblockUser(c.Request.Context(), &authpb.UnblockUserRequest{
		UserId:       userID,
		TargetUserId: targetID,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to unblock user")
		return
	}

	c.JSON(http.StatusOK, gin.H{"success": resp.Success})
}

// GetBlockedUsers returns the list of blocked users.
func (h *AuthHandler) GetBlockedUsers(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}

	resp, err := h.client.GetBlockedUsers(c.Request.Context(), &authpb.GetBlockedUsersRequest{
		UserId: userID,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to get blocked users list")
		return
	}

	c.JSON(http.StatusOK, resp.BlockedUserIds)
}

// RegisterPhone registers a user's phone number and initiates OTP dispatch.
func (h *AuthHandler) RegisterPhone(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	var req struct {
		Phone string `json:"phone" binding:"required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	resp, err := h.client.RegisterPhone(c.Request.Context(), &authpb.RegisterPhoneRequest{
		UserId: userID,
		Phone:  req.Phone,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to register phone number")
		return
	}

	c.JSON(http.StatusOK, gin.H{"success": resp.Success})
}

// VerifyPhoneOTP verifies the mock OTP sent to the user's phone.
func (h *AuthHandler) VerifyPhoneOTP(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	var req struct {
		Phone string `json:"phone" binding:"required"`
		Otp   string `json:"otp" binding:"required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	resp, err := h.client.VerifyPhoneOTP(c.Request.Context(), &authpb.VerifyPhoneOTPRequest{
		UserId: userID,
		Phone:  req.Phone,
		Otp:    req.Otp,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to verify OTP")
		return
	}

	c.JSON(http.StatusOK, gin.H{"valid": resp.Valid})
}

// SubscribePush registers a user's push token.
func (h *AuthHandler) SubscribePush(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	var req struct {
		PushToken string `json:"push_token" binding:"required"`
		Platform  string `json:"platform" binding:"required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	resp, err := h.client.SubscribePush(c.Request.Context(), &authpb.SubscribePushRequest{
		UserId:    userID,
		PushToken: req.PushToken,
		Platform:  req.Platform,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to subscribe to push notifications")
		return
	}

	c.JSON(http.StatusOK, gin.H{"success": resp.Success})
}
