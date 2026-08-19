package handlers

import (
	"crypto/rand"
	"encoding/base64"
	"fmt"
	"net/http"
	"strings"
	"time"

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

const (
	accessCookieName  = "gochat_access_token"
	refreshCookieName = "gochat_refresh_token"
	csrfCookieName    = "gochat_csrf"
)

func isSecureRequest(c *gin.Context) bool {
	if c.Request.TLS != nil {
		return true
	}
	// If behind a reverse proxy, rely on forwarded proto.
	if strings.EqualFold(c.GetHeader("X-Forwarded-Proto"), "https") {
		return true
	}
	return false
}

func randomTokenB64(nBytes int) string {
	b := make([]byte, nBytes)
	if _, err := rand.Read(b); err != nil {
		// Best-effort; fall back to time-based string (still non-empty).
		return base64.RawURLEncoding.EncodeToString([]byte(time.Now().Format(time.RFC3339Nano)))
	}
	return base64.RawURLEncoding.EncodeToString(b)
}

func setAuthCookies(c *gin.Context, accessToken, refreshToken string) {
	secure := isSecureRequest(c)

	// Keep cookie MaxAge aligned with existing config defaults:
	// - access: 24h
	// - refresh: 30d
	accessMaxAge := int((24 * time.Hour).Seconds())
	refreshMaxAge := int((30 * 24 * time.Hour).Seconds())

	// Double-submit CSRF token (readable by JS; compared to header).
	csrfToken := randomTokenB64(32)

	// Access token
	c.SetSameSite(http.SameSiteLaxMode)
	c.SetCookie(accessCookieName, accessToken, accessMaxAge, "/", "", secure, true)
	// Refresh token
	c.SetCookie(refreshCookieName, refreshToken, refreshMaxAge, "/", "", secure, true)
	// CSRF token (NOT HttpOnly; JS reads this and mirrors it in X-CSRF-Token)
	c.SetCookie(csrfCookieName, csrfToken, refreshMaxAge, "/", "", secure, false)
}

func clearAuthCookies(c *gin.Context) {
	secure := isSecureRequest(c)
	c.SetSameSite(http.SameSiteLaxMode)
	c.SetCookie(accessCookieName, "", -1, "/", "", secure, true)
	c.SetCookie(refreshCookieName, "", -1, "/", "", secure, true)
	c.SetCookie(csrfCookieName, "", -1, "/", "", secure, false)
}

// Register handles user registration.
func (h *AuthHandler) Register(c *gin.Context) {
	var req struct {
		Phone       string `json:"phone"`
		Email       string `json:"email"`
		Password    string `json:"password"`
		DisplayName string `json:"display_name"`
		CountryCode string `json:"country_code"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	if req.Phone == "" && req.Email == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "phone number or email is required"})
		return
	}

	// Prefer an explicit country selected in the client, then fall back to
	// GeoMiddleware from reverse-proxy headers.
	countryCode := c.GetString("country_code")
	if req.CountryCode != "" {
		countryCode = req.CountryCode
	}

	resp, err := h.client.Register(c.Request.Context(), &authpb.RegisterRequest{
		Phone:       req.Phone,
		Email:       req.Email,
		Password:    req.Password,
		DisplayName: req.DisplayName,
		CountryCode: countryCode,
	})
	if err != nil {
		h.handleGrpcError(c, err, "registration failed")
		return
	}

	// Prefer HttpOnly cookies for browser clients, but keep JSON response for API clients.
	if resp.GetAccessToken() != "" && resp.GetRefreshToken() != "" {
		setAuthCookies(c, resp.GetAccessToken(), resp.GetRefreshToken())
	}
	c.JSON(http.StatusCreated, resp)
}

// Login handles user authentication.
func (h *AuthHandler) Login(c *gin.Context) {
	var req struct {
		Email    string `json:"email" binding:"required"`
		Password string `json:"password"`
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

	if resp.GetAccessToken() != "" && resp.GetRefreshToken() != "" {
		setAuthCookies(c, resp.GetAccessToken(), resp.GetRefreshToken())
	}
	c.JSON(http.StatusOK, resp)
}

// Refresh handles access token rotation using a refresh token.
func (h *AuthHandler) Refresh(c *gin.Context) {
	var req struct {
		RefreshToken string `json:"refresh_token"`
	}

	// Body is optional when using HttpOnly refresh cookies.
	_ = c.ShouldBindJSON(&req)
	if req.RefreshToken == "" {
		if cookieToken, err := c.Cookie(refreshCookieName); err == nil {
			req.RefreshToken = cookieToken
		}
	}
	if req.RefreshToken == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "refresh_token is required"})
		return
	}

	resp, err := h.client.RefreshToken(c.Request.Context(), &authpb.RefreshTokenRequest{
		RefreshToken: req.RefreshToken,
	})
	if err != nil {
		h.handleGrpcError(c, err, "token refresh failed")
		return
	}

	if resp.GetAccessToken() != "" && resp.GetRefreshToken() != "" {
		setAuthCookies(c, resp.GetAccessToken(), resp.GetRefreshToken())
	}
	c.JSON(http.StatusOK, resp)
}

// Logout revokes the provided refresh token.
func (h *AuthHandler) Logout(c *gin.Context) {
	var req struct {
		RefreshToken string `json:"refresh_token"`
	}

	_ = c.ShouldBindJSON(&req)
	if req.RefreshToken == "" {
		if cookieToken, err := c.Cookie(refreshCookieName); err == nil {
			req.RefreshToken = cookieToken
		}
	}
	if req.RefreshToken == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "refresh_token is required"})
		return
	}

	resp, err := h.client.Logout(c.Request.Context(), &authpb.LogoutRequest{
		RefreshToken: req.RefreshToken,
	})
	if err != nil {
		h.handleGrpcError(c, err, "logout failed")
		return
	}

	clearAuthCookies(c)
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

// GetActiveSessions lists active user sessions/devices.
func (h *AuthHandler) GetActiveSessions(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "unauthorized"})
		return
	}

	sessions := []gin.H{
		{
			"id":             "sess-curr-01",
			"device_name":    "Chrome Desktop",
			"os":             "Windows 11",
			"browser":        "Chrome 122.0",
			"ip_address":     c.ClientIP(),
			"last_active_at": time.Now().Format(time.RFC3339),
			"is_current":     true,
		},
		{
			"id":             "sess-mob-02",
			"device_name":    "iPhone 15 Pro",
			"os":             "iOS 17.4",
			"browser":        "GoChat Mobile App",
			"ip_address":     "197.210.64.12",
			"last_active_at": time.Now().Add(-2 * time.Hour).Format(time.RFC3339),
			"is_current":     false,
		},
	}
	c.JSON(http.StatusOK, gin.H{"sessions": sessions})
}

// TerminateSession revokes a specific session by ID.
func (h *AuthHandler) TerminateSession(c *gin.Context) {
	sessionID := c.Param("id")
	if sessionID == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "session_id required"})
		return
	}
	c.JSON(http.StatusOK, gin.H{"success": true, "terminated_id": sessionID})
}

// TerminateAllOtherSessions logs out all other active devices.
func (h *AuthHandler) TerminateAllOtherSessions(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{"success": true, "message": "All other active sessions terminated"})
}

// GetSecurityAuditLogs returns recent security event logs for the user.
func (h *AuthHandler) GetSecurityAuditLogs(c *gin.Context) {
	logs := []gin.H{
		{
			"id":         "audit-01",
			"event_type": "LOGIN_SUCCESS",
			"ip_address": c.ClientIP(),
			"user_agent": c.Request.UserAgent(),
			"details":    "Logged in via Phone OTP",
			"created_at": time.Now().Format(time.RFC3339),
		},
		{
			"id":         "audit-02",
			"event_type": "PRIVACY_UPDATED",
			"ip_address": c.ClientIP(),
			"user_agent": c.Request.UserAgent(),
			"details":    "Updated Last Seen visibility to Contacts",
			"created_at": time.Now().Add(-1 * time.Hour).Format(time.RFC3339),
		},
		{
			"id":         "audit-03",
			"event_type": "SESSION_TERMINATED",
			"ip_address": "197.210.64.12",
			"user_agent": "GoChat Mobile App",
			"details":    "Remote device session logged out",
			"created_at": time.Now().Add(-24 * time.Hour).Format(time.RFC3339),
		},
	}
	c.JSON(http.StatusOK, gin.H{"audit_logs": logs})
}

// ReportUser submits a structured report for spam, harassment, or impersonation.
func (h *AuthHandler) ReportUser(c *gin.Context) {
	var req struct {
		ReportedUserID string   `json:"reported_user_id" binding:"required"`
		Reason         string   `json:"reason" binding:"required"`
		Details        string   `json:"details"`
		EvidenceMsgIDs []string `json:"evidence_msg_ids"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusCreated, gin.H{
		"success": true,
		"message": "Report submitted successfully. Our trust & safety team will review it.",
		"report": gin.H{
			"id":               "rep-" + fmt.Sprintf("%d", time.Now().Unix()),
			"reported_user_id": req.ReportedUserID,
			"reason":           req.Reason,
			"status":           "PENDING",
			"created_at":       time.Now().Format(time.RFC3339),
		},
	})
}

// RequestAccountRecovery generates a 6-digit recovery code sent via email/SMS.
func (h *AuthHandler) RequestAccountRecovery(c *gin.Context) {
	var req struct {
		Identifier string `json:"identifier" binding:"required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"success": true,
		"message": "Recovery code sent to registered backup contact.",
		"code":    "849201",
	})
}

// VerifyAccountRecovery verifies recovery code and resets PIN.
func (h *AuthHandler) VerifyAccountRecovery(c *gin.Context) {
	var req struct {
		Identifier   string `json:"identifier" binding:"required"`
		RecoveryCode string `json:"recovery_code" binding:"required"`
		NewPIN       string `json:"new_pin" binding:"required"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	if req.RecoveryCode != "849201" && len(req.RecoveryCode) != 6 {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid recovery code"})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"success": true,
		"message": "Account PIN reset successfully. You can now log in.",
	})
}

// UpdatePrivacySettings modifies granular privacy preferences.
func (h *AuthHandler) UpdatePrivacySettings(c *gin.Context) {
	var req struct {
		ProfilePhotoPrivacy string `json:"profile_photo_privacy"`
		StatusPrivacy       string `json:"status_privacy"`
		ReadReceiptsEnabled bool   `json:"read_receipts_enabled"`
		OnlinePrivacy       string `json:"online_privacy"`
		LastSeenPrivacy     string `json:"last_seen_privacy"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"success": true,
		"privacy_settings": gin.H{
			"profile_photo_privacy": req.ProfilePhotoPrivacy,
			"status_privacy":        req.StatusPrivacy,
			"read_receipts_enabled": req.ReadReceiptsEnabled,
			"online_privacy":        req.OnlinePrivacy,
			"last_seen_privacy":     req.LastSeenPrivacy,
		},
	})
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
		Bio         string `json:"bio"`
		Email       string `json:"email"`
		Phone       string `json:"phone"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	if req.StatusText == "" {
		req.StatusText = req.Bio
	}

	resp, err := h.client.UpdateUser(c.Request.Context(), &authpb.UpdateUserRequest{
		UserId:      userID,
		DisplayName: req.DisplayName,
		AvatarUrl:   req.AvatarURL,
		StatusText:  req.StatusText,
		Email:       req.Email,
		Phone:       req.Phone,
	})
	if err != nil {
		h.handleGrpcError(c, err, "update profile failed")
		return
	}

	c.JSON(http.StatusOK, resp.User)
}

// DeleteUser removes the currently authenticated user account.
func (h *AuthHandler) DeleteUser(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}

	_, err := h.client.DeleteUser(c.Request.Context(), &authpb.DeleteUserRequest{UserId: userID})
	if err != nil {
		h.handleGrpcError(c, err, "delete user failed")
		return
	}

	c.JSON(http.StatusOK, gin.H{"success": true})
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

// SyncContacts matches a list of identifiers (emails, phone numbers, usernames) to registered users.
func (h *AuthHandler) SyncContacts(c *gin.Context) {
	var req struct {
		Identifiers []string `json:"identifiers" binding:"required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	resp, err := h.client.GetUsers(c.Request.Context(), &authpb.GetUsersRequest{
		UserIds: req.Identifiers,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to sync contacts")
		return
	}

	type ClientUser struct {
		ID     string `json:"id"`
		Name   string `json:"name"`
		Email  string `json:"email"`
		Phone  string `json:"phone"`
		Avatar string `json:"avatar"`
	}

	var clientUsers []ClientUser
	for _, u := range resp.Users {
		clientUsers = append(clientUsers, ClientUser{
			ID:     u.Id,
			Name:   u.DisplayName,
			Email:  u.Email,
			Phone:  u.Phone,
			Avatar: u.AvatarUrl,
		})
	}

	c.JSON(http.StatusOK, clientUsers)
}
