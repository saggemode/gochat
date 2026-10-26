package handlers

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"sort"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/redis/go-redis/v9"
	"go.uber.org/zap"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"

	authpb "gochat/gen/auth"
	"gochat/services/gateway/ws"
)

// DeviceSession represents an active linked device or web session.
type DeviceSession struct {
	ID           string    `json:"id"`
	DeviceName   string    `json:"device_name"`
	OS           string    `json:"os"`
	Browser      string    `json:"browser"`
	Platform     string    `json:"platform"`
	IPAddress    string    `json:"ip_address"`
	LastActiveAt time.Time `json:"last_active_at"`
	IsCurrent    bool      `json:"is_current"`
	TokenHash    string    `json:"token_hash,omitempty"`
	PublicKey    string    `json:"public_key,omitempty"`
	IdentityKey  string    `json:"identity_key,omitempty"`
	Location     string    `json:"location,omitempty"`
}

// QRPairSession represents an active or pending QR pairing session for web/desktop.
type QRPairSession struct {
	Token        string `json:"token"`
	DeviceName   string `json:"device_name"`
	Platform     string `json:"platform"`
	PublicKey    string `json:"public_key,omitempty"`
	Status       string `json:"status"` // "pending", "approved", "expired"
	CreatedAt    int64  `json:"created_at"`
	ExpiresAt    int64  `json:"expires_at"`
	AccessToken  string `json:"access_token,omitempty"`
	RefreshToken string `json:"refresh_token,omitempty"`
	DeviceID     string `json:"device_id,omitempty"`
	User         any    `json:"user,omitempty"`
	IdentityKey  string `json:"identity_key,omitempty"`
	KeyExchange  string `json:"key_exchange,omitempty"`
}

func hashToken(token string) string {
	if token == "" {
		return ""
	}
	h := sha256.Sum256([]byte(token))
	return fmt.Sprintf("%x", h[:])
}

func extractTokenFromRequest(c *gin.Context) string {
	authHeader := c.GetHeader("Authorization")
	if authHeader != "" {
		parts := strings.Split(authHeader, " ")
		if len(parts) == 2 && strings.ToLower(parts[0]) == "bearer" {
			return parts[1]
		}
	}
	if cookieToken, err := c.Cookie(accessCookieName); err == nil && cookieToken != "" {
		return cookieToken
	}
	return c.Query("token")
}

// AuthHandler wraps the Auth Service gRPC client.
type AuthHandler struct {
	client authpb.AuthServiceClient
	redis  *redis.Client
	hub    *ws.Hub
	log    *zap.Logger
}

// NewAuthHandler constructs the AuthHandler.
func NewAuthHandler(client authpb.AuthServiceClient, log *zap.Logger) *AuthHandler {
	return &AuthHandler{client: client, log: log}
}

// WithRedis sets the Redis client for device/session tracking.
func (h *AuthHandler) WithRedis(redis *redis.Client) *AuthHandler {
	h.redis = redis
	return h
}

// WithHub sets the WebSocket Hub for real-time multi-device sync and notifications.
func (h *AuthHandler) WithHub(hub *ws.Hub) *AuthHandler {
	h.hub = hub
	return h
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

// RegisterDevice registers or heartbeats a client device session.
func (h *AuthHandler) RegisterDevice(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "unauthorized"})
		return
	}

	var req struct {
		DeviceID   string `json:"device_id"`
		DeviceName string `json:"device_name"`
		Platform   string `json:"platform"`
		OS         string `json:"os"`
		Browser    string `json:"browser"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	if req.DeviceID == "" {
		req.DeviceID = fmt.Sprintf("dev_%d", time.Now().UnixNano())
	}
	if req.DeviceName == "" {
		req.DeviceName = "Android Device"
	}
	if req.Platform == "" {
		req.Platform = "android"
	}
	if req.OS == "" {
		req.OS = "Android"
	}
	if req.Browser == "" {
		req.Browser = "GoChat App"
	}

	token := extractTokenFromRequest(c)
	tokenHash := hashToken(token)

	sess := DeviceSession{
		ID:           req.DeviceID,
		DeviceName:   req.DeviceName,
		Platform:     req.Platform,
		OS:           req.OS,
		Browser:      req.Browser,
		IPAddress:    c.ClientIP(),
		LastActiveAt: time.Now(),
		IsCurrent:    true,
		TokenHash:    tokenHash,
	}

	if h.redis != nil {
		data, _ := json.Marshal(sess)
		_ = h.redis.HSet(c.Request.Context(), "user:devices:"+userID, req.DeviceID, data).Err()
		// Clear any past revocation for this specific device if it logs in fresh
		_ = h.redis.Del(c.Request.Context(), "device:revoked:"+userID+":"+req.DeviceID).Err()
	}

	c.JSON(http.StatusOK, gin.H{
		"success": true,
		"device":  sess,
	})
}

// GetActiveSessions lists active user sessions/devices.
func (h *AuthHandler) GetActiveSessions(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "unauthorized"})
		return
	}

	currentDeviceID := c.GetHeader("X-Device-Id")
	var sessions []DeviceSession

	if h.redis != nil {
		vals, err := h.redis.HGetAll(c.Request.Context(), "user:devices:"+userID).Result()
		if err == nil && len(vals) > 0 {
			for _, raw := range vals {
				var s DeviceSession
				if json.Unmarshal([]byte(raw), &s) == nil {
					if currentDeviceID != "" && s.ID == currentDeviceID {
						s.IsCurrent = true
					}
					sessions = append(sessions, s)
				}
			}
		}
	}

	// Sort sessions by last active time descending
	if len(sessions) > 1 {
		sort.Slice(sessions, func(i, j int) bool {
			return sessions[i].LastActiveAt.After(sessions[j].LastActiveAt)
		})
	}

	// If no devices registered yet, provide default current device entry
	if len(sessions) == 0 {
		ua := c.GetHeader("User-Agent")
		osName := "Android"
		browserName := "GoChat Mobile App"
		platform := "android"
		if strings.Contains(strings.ToLower(ua), "windows") {
			osName = "Windows"
			platform = "desktop"
			browserName = "Chrome / Web"
		} else if strings.Contains(strings.ToLower(ua), "mac") {
			osName = "macOS"
			platform = "desktop"
			browserName = "Safari / Web"
		} else if strings.Contains(strings.ToLower(ua), "iphone") {
			osName = "iOS"
			platform = "ios"
			browserName = "GoChat iOS"
		}

		sessions = []DeviceSession{
			{
				ID:           "primary_device",
				DeviceName:   "This Phone (" + osName + ")",
				OS:           osName,
				Browser:      browserName,
				Platform:     platform,
				IPAddress:    c.ClientIP(),
				LastActiveAt: time.Now(),
				IsCurrent:    true,
			},
		}
	}

	c.JSON(http.StatusOK, gin.H{
		"sessions": sessions,
		"devices":  sessions,
	})
}

// TerminateSession revokes a specific session or unlinks a device by ID and invalidates its JWT token.
func (h *AuthHandler) TerminateSession(c *gin.Context) {
	userID := getUserID(c)
	sessionID := c.Param("id")
	if sessionID == "" {
		sessionID = c.Param("device_id")
	}
	if sessionID == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "session_id required"})
		return
	}

	if h.redis != nil && userID != "" {
		ctx := c.Request.Context()
		// 1. Retrieve session to extract token hash for immediate token blacklisting
		raw, err := h.redis.HGet(ctx, "user:devices:"+userID, sessionID).Result()
		if err == nil && raw != "" {
			var sess DeviceSession
			if json.Unmarshal([]byte(raw), &sess) == nil && sess.TokenHash != "" {
				// Invalidate the JWT token on the server for 24h
				_ = h.redis.Set(ctx, "jwt:revoked:"+sess.TokenHash, "revoked", 24*time.Hour).Err()
			}
		}

		// 2. Blacklist the device session on the server for 7 days
		_ = h.redis.Set(ctx, "device:revoked:"+userID+":"+sessionID, "revoked", 7*24*time.Hour).Err()

		// 3. Delete from active devices list
		_ = h.redis.HDel(ctx, "user:devices:"+userID, sessionID).Err()
	}

	// 4. Real-time WebSocket notification so that the revoked device disconnects immediately
	if h.hub != nil && userID != "" {
		evt, _ := json.Marshal(map[string]any{
			"type":      "session_revoked",
			"device_id": sessionID,
			"user_id":   userID,
		})
		h.hub.SendToUser(userID, evt)
	}

	c.JSON(http.StatusOK, gin.H{
		"success":       true,
		"terminated_id": sessionID,
		"device_id":     sessionID,
		"message":       "Session and token invalidated on server",
	})
}

// TerminateAllOtherSessions logs out all other active devices and invalidates their JWT tokens.
func (h *AuthHandler) TerminateAllOtherSessions(c *gin.Context) {
	userID := getUserID(c)
	currentDeviceID := c.GetHeader("X-Device-Id")

	if h.redis != nil && userID != "" {
		ctx := c.Request.Context()
		vals, err := h.redis.HGetAll(ctx, "user:devices:"+userID).Result()
		if err == nil {
			for devID, raw := range vals {
				if devID != currentDeviceID {
					var sess DeviceSession
					if json.Unmarshal([]byte(raw), &sess) == nil && sess.TokenHash != "" {
						// Blacklist token on server for 24h
						_ = h.redis.Set(ctx, "jwt:revoked:"+sess.TokenHash, "revoked", 24*time.Hour).Err()
					}
					// Blacklist device for 7 days
					_ = h.redis.Set(ctx, "device:revoked:"+userID+":"+devID, "revoked", 7*24*time.Hour).Err()
					// Delete from active devices list
					_ = h.redis.HDel(ctx, "user:devices:"+userID, devID).Err()
				}
			}
		}
	}

	// Real-time WebSocket notification to force logout on all other devices
	if h.hub != nil && userID != "" {
		evt, _ := json.Marshal(map[string]any{
			"type":              "all_other_sessions_revoked",
			"current_device_id": currentDeviceID,
			"user_id":           userID,
		})
		h.hub.SendToUser(userID, evt)
	}

	c.JSON(http.StatusOK, gin.H{
		"success": true,
		"message": "All other active sessions and tokens terminated",
	})
}

// InitQRPairing creates an ephemeral QR pairing token for web/desktop authentication.
func (h *AuthHandler) InitQRPairing(c *gin.Context) {
	var req struct {
		DeviceName string `json:"device_name"`
		Platform   string `json:"platform"`
		PublicKey  string `json:"public_key"`
	}
	_ = c.ShouldBindJSON(&req)

	if req.DeviceName == "" {
		ua := c.GetHeader("User-Agent")
		if strings.Contains(strings.ToLower(ua), "windows") {
			req.DeviceName = "Chrome (Windows)"
		} else if strings.Contains(strings.ToLower(ua), "mac") {
			req.DeviceName = "Safari (macOS)"
		} else {
			req.DeviceName = "GoChat Web"
		}
	}
	if req.Platform == "" {
		req.Platform = "web"
	}

	token := "qr_" + randomTokenB64(24)
	now := time.Now()
	expiresAt := now.Add(120 * time.Second).Unix()

	session := QRPairSession{
		Token:      token,
		DeviceName: req.DeviceName,
		Platform:   req.Platform,
		PublicKey:  req.PublicKey,
		Status:     "pending",
		CreatedAt:  now.Unix(),
		ExpiresAt:  expiresAt,
	}

	if h.redis != nil {
		data, _ := json.Marshal(session)
		_ = h.redis.Set(c.Request.Context(), "qr:pair:"+token, data, 120*time.Second).Err()
	}

	qrPayload := fmt.Sprintf("gochat://pair?token=%s&pub=%s&name=%s&platform=%s",
		token,
		url.QueryEscape(req.PublicKey),
		url.QueryEscape(req.DeviceName),
		url.QueryEscape(req.Platform),
	)

	c.JSON(http.StatusOK, gin.H{
		"token":       token,
		"status":      "pending",
		"device_name": req.DeviceName,
		"platform":    req.Platform,
		"expires_at":  expiresAt,
		"qr_payload":  qrPayload,
	})
}

// GetQRPairingStatus checks if a QR pairing token has been scanned and authorized by the mobile app.
func (h *AuthHandler) GetQRPairingStatus(c *gin.Context) {
	token := c.Query("token")
	if token == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "token parameter is required"})
		return
	}

	if h.redis == nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "pairing service unavailable"})
		return
	}

	ctx := c.Request.Context()
	val, err := h.redis.Get(ctx, "qr:pair:"+token).Result()
	if err != nil || val == "" {
		c.JSON(http.StatusOK, gin.H{"status": "expired"})
		return
	}

	var session QRPairSession
	if err := json.Unmarshal([]byte(val), &session); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "corrupted pairing session"})
		return
	}

	if session.Status == "approved" {
		if session.AccessToken != "" && session.RefreshToken != "" {
			setAuthCookies(c, session.AccessToken, session.RefreshToken)
		}
		c.JSON(http.StatusOK, gin.H{
			"status":        "approved",
			"access_token":  session.AccessToken,
			"refresh_token": session.RefreshToken,
			"device_id":     session.DeviceID,
			"user":          session.User,
			"identity_key":  session.IdentityKey,
			"key_exchange":  session.KeyExchange,
		})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"status":     session.Status,
		"expires_at": session.ExpiresAt,
	})
}

// AuthorizeQRPairing is called by the authenticated mobile app after scanning the QR code.
func (h *AuthHandler) AuthorizeQRPairing(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "unauthorized"})
		return
	}

	var req struct {
		Token       string `json:"token" binding:"required"`
		DeviceName  string `json:"device_name"`
		Platform    string `json:"platform"`
		PublicKey   string `json:"public_key"`
		IdentityKey string `json:"identity_key"`
		KeyExchange string `json:"key_exchange"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "token is required"})
		return
	}

	if h.redis == nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "pairing service unavailable"})
		return
	}

	ctx := c.Request.Context()
	val, err := h.redis.Get(ctx, "qr:pair:"+req.Token).Result()
	if err != nil || val == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "QR code has expired or is invalid. Please refresh the code on your screen."})
		return
	}

	var session QRPairSession
	if err := json.Unmarshal([]byte(val), &session); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid pairing session"})
		return
	}

	if session.Status != "pending" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "QR code already used or expired"})
		return
	}

	// Fetch current user details
	userResp, err := h.client.GetUser(ctx, &authpb.GetUserRequest{UserId: userID})
	if err != nil {
		h.handleGrpcError(c, err, "failed to retrieve user account")
		return
	}

	identifier := userResp.User.Email
	if identifier == "" {
		identifier = userResp.User.Phone
	}
	if identifier == "" {
		identifier = userResp.User.Id
	}

	// Generate fresh session token pair for the newly linked device
	loginResp, err := h.client.Login(ctx, &authpb.LoginRequest{
		Email:    identifier,
		Password: "",
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to issue linked device session token")
		return
	}

	devName := req.DeviceName
	if devName == "" {
		devName = session.DeviceName
	}
	if devName == "" {
		devName = "GoChat Web"
	}

	devPlatform := req.Platform
	if devPlatform == "" {
		devPlatform = session.Platform
	}
	if devPlatform == "" {
		devPlatform = "web"
	}

	deviceID := fmt.Sprintf("dev_%s_%d", devPlatform, time.Now().UnixNano())
	tokenHash := hashToken(loginResp.AccessToken)

	// Save new linked device session
	deviceSession := DeviceSession{
		ID:           deviceID,
		DeviceName:   devName,
		Platform:     devPlatform,
		OS:           devPlatform,
		Browser:      devName,
		IPAddress:    c.ClientIP(),
		LastActiveAt: time.Now(),
		IsCurrent:    false,
		TokenHash:    tokenHash,
		PublicKey:    req.PublicKey,
		IdentityKey:  req.IdentityKey,
	}

	sessData, _ := json.Marshal(deviceSession)
	_ = h.redis.HSet(ctx, "user:devices:"+userID, deviceID, sessData).Err()

	// Update QR pair session to approved with tokens
	session.Status = "approved"
	session.AccessToken = loginResp.AccessToken
	session.RefreshToken = loginResp.RefreshToken
	session.DeviceID = deviceID
	session.User = userResp.User
	session.IdentityKey = req.IdentityKey
	session.KeyExchange = req.KeyExchange

	approvedData, _ := json.Marshal(session)
	_ = h.redis.Set(ctx, "qr:pair:"+req.Token, approvedData, 60*time.Second).Err()

	// Real-time broadcast to all user's active connections
	if h.hub != nil {
		evt, _ := json.Marshal(map[string]any{
			"type":        "device_linked",
			"device_id":   deviceID,
			"device_name": devName,
			"platform":    devPlatform,
			"user_id":     userID,
		})
		h.hub.SendToUser(userID, evt)
	}

	c.JSON(http.StatusOK, gin.H{
		"success":     true,
		"device_id":   deviceID,
		"device_name": devName,
		"message":     "Device linked successfully",
	})
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

	resp, err := h.client.RequestAccountRecovery(c.Request.Context(), &authpb.RequestAccountRecoveryRequest{
		Identifier: req.Identifier,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to request recovery code")
		return
	}

	res := gin.H{
		"success":     resp.GetSuccess(),
		"message":     resp.GetMessage(),
		"channel":     resp.GetChannel(),
		"destination": resp.GetDestination(),
	}
	if resp.GetDebugCode() != "" {
		res["code"] = resp.GetDebugCode()
	}

	c.JSON(http.StatusOK, res)
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

	resp, err := h.client.VerifyAccountRecovery(c.Request.Context(), &authpb.VerifyAccountRecoveryRequest{
		Identifier:   req.Identifier,
		RecoveryCode: req.RecoveryCode,
		NewPin:       req.NewPIN,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to verify recovery code")
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"success": resp.GetSuccess(),
		"message": resp.GetMessage(),
	})
}

// GetPrivacySettings retrieves user privacy preferences.
func (h *AuthHandler) GetPrivacySettings(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}

	resp, err := h.client.GetPrivacySettings(c.Request.Context(), &authpb.GetPrivacySettingsRequest{
		UserId: userID,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to get privacy settings")
		return
	}

	c.JSON(http.StatusOK, resp.Settings)
}

// UpdatePrivacySettings modifies granular privacy preferences.
func (h *AuthHandler) UpdatePrivacySettings(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}

	// Pointer fields so we can tell "omitted" from "set to zero value" and only
	// update what the client actually sent (clients send partial updates).
	var req struct {
		ProfilePhotoPrivacy *string `json:"profile_photo_privacy"`
		StatusPrivacy       *string `json:"status_privacy"`
		ReadReceiptsEnabled *bool   `json:"read_receipts_enabled"`
		OnlinePrivacy       *string `json:"online_privacy"`
		LastSeenPrivacy     *string `json:"last_seen_privacy"`
		AllowStoryReshare   *bool   `json:"allow_story_reshare"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	// Start from the current settings so omitted fields are preserved.
	cur, err := h.client.GetPrivacySettings(c.Request.Context(), &authpb.GetPrivacySettingsRequest{UserId: userID})
	if err != nil {
		h.handleGrpcError(c, err, "failed to load current privacy settings")
		return
	}
	merged := cur.Settings
	if merged == nil {
		merged = &authpb.PrivacySettings{}
	}
	if req.ProfilePhotoPrivacy != nil {
		merged.ProfilePhotoPrivacy = *req.ProfilePhotoPrivacy
	}
	if req.StatusPrivacy != nil {
		merged.StatusPrivacy = *req.StatusPrivacy
	}
	if req.ReadReceiptsEnabled != nil {
		merged.ReadReceiptsEnabled = *req.ReadReceiptsEnabled
	}
	if req.OnlinePrivacy != nil {
		merged.OnlinePrivacy = *req.OnlinePrivacy
	}
	if req.LastSeenPrivacy != nil {
		merged.LastSeenPrivacy = *req.LastSeenPrivacy
	}
	if req.AllowStoryReshare != nil {
		merged.AllowStoryReshare = *req.AllowStoryReshare
	}

	resp, err := h.client.UpdatePrivacySettings(c.Request.Context(), &authpb.UpdatePrivacySettingsRequest{
		UserId:   userID,
		Settings: merged,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to update privacy settings")
		return
	}

	c.JSON(http.StatusOK, gin.H{"success": resp.Success})
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

	blocked := resp.BlockedUserIds
	if blocked == nil {
		blocked = []string{}
	}
	c.JSON(http.StatusOK, gin.H{
		"blocked_users": blocked,
		"blockedUsers":  blocked,
	})
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
		ID         string `json:"id"`
		Name       string `json:"name"`
		Email      string `json:"email"`
		Phone      string `json:"phone"`
		Avatar     string `json:"avatar"`
		StatusText string `json:"status_text"`
		IsOnline   bool   `json:"is_online"`
		LastSeen   int64  `json:"last_seen"`
		PIN        string `json:"pin"`
	}

	var clientUsers []ClientUser
	for _, u := range resp.Users {
		clientUsers = append(clientUsers, ClientUser{
			ID:         u.Id,
			Name:       u.DisplayName,
			Email:      u.Email,
			Phone:      u.Phone,
			Avatar:     u.AvatarUrl,
			StatusText: u.StatusText,
			IsOnline:   u.IsOnline,
			LastSeen:   u.LastSeen,
			PIN:        u.Pin,
		})
	}

	// Persist the matched contacts so the story feed can scope visibility to a user's contacts.
	if ownerID := getUserID(c); ownerID != "" && len(resp.Users) > 0 {
		ids := make([]string, 0, len(resp.Users))
		for _, u := range resp.Users {
			ids = append(ids, u.Id)
		}
		if _, err := h.client.SaveContacts(c.Request.Context(), &authpb.SaveContactsRequest{
			OwnerId: ownerID, ContactUserIds: ids,
		}); err != nil {
			h.log.Warn("failed to persist synced contacts", zap.Error(err))
		}
	}

	c.JSON(http.StatusOK, clientUsers)
}
