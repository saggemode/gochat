package main

import (
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"os"
	"sync"
	"time"
)

// DeviceToken holds registered push notification token info
type DeviceToken struct {
	UserID    string    `json:"user_id"`
	Token     string    `json:"token"`
	Platform  string    `json:"platform"` // "android", "ios", "web"
	VoipToken string    `json:"voip_token,omitempty"`
	UpdatedAt time.Time `json:"updated_at"`
}

// PushNotificationPayload represents an outgoing push notification
type PushNotificationPayload struct {
	RecipientID  string                 `json:"recipient_id"`
	Title        string                 `json:"title"`
	Body         string                 `json:"body"`
	Category     string                 `json:"category"` // "message", "call", "system"
	Data         map[string]interface{} `json:"data,omitempty"`
	IsHighPriority bool                 `json:"is_high_priority"`
}

// VoipCallPushPayload represents an incoming VoIP call push alert
type VoipCallPushPayload struct {
	CallID       string `json:"call_id"`
	CallerID     string `json:"caller_id"`
	CallerName   string `json:"caller_name"`
	CallerAvatar string `json:"caller_avatar"`
	CallerPin    string `json:"caller_pin"`
	RecipientID  string `json:"recipient_id"`
	CallType     string `json:"call_type"` // "audio", "video"
	IsEncrypted  bool   `json:"is_encrypted"`
}

// In-memory token store (with DB sync capability)
var (
	tokenStore = make(map[string][]DeviceToken) // userID -> tokens
	storeMutex sync.RWMutex
)

func main() {
	port := os.Getenv("PORT")
	if port == "" {
		port = "8092"
	}

	mux := http.NewServeMux()

	// Health Check
	mux.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]string{"status": "healthy", "service": "notification"})
	})

	// Token Registration
	mux.HandleFunc("/api/v1/notifications/tokens", handleRegisterToken)

	// Send Push Notification
	mux.HandleFunc("/api/v1/notifications/send", handleSendPush)

	// Send High-Priority VoIP Call Push (CallKit / Telecom)
	mux.HandleFunc("/api/v1/notifications/voip-call", handleSendVoipPush)

	// Notification Configuration / Capabilities
	mux.HandleFunc("/api/v1/notifications/config", handleGetConfig)

	server := &http.Server{
		Addr:         ":" + port,
		Handler:      corsMiddleware(mux),
		ReadTimeout:  10 * time.Second,
		WriteTimeout: 10 * time.Second,
	}

	log.Printf("🚀 GoChat Notification Service listening on port %s", port)
	if err := server.ListenAndServe(); err != nil {
		log.Fatalf("Server error: %v", err)
	}
}

func corsMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Access-Control-Allow-Origin", "*")
		w.Header().Set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
		w.Header().Set("Access-Control-Allow-Headers", "Content-Type, Authorization, X-User-ID")

		if r.Method == http.MethodOptions {
			w.WriteHeader(http.StatusOK)
			return
		}
		next.ServeHTTP(w, r)
	})
}

// handleRegisterToken stores or updates a device push token
func handleRegisterToken(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	var req DeviceToken
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "Invalid request body", http.StatusBadRequest)
		return
	}

	if req.UserID == "" || req.Token == "" {
		http.Error(w, "user_id and token are required", http.StatusBadRequest)
		return
	}

	if req.Platform == "" {
		req.Platform = "android"
	}
	req.UpdatedAt = time.Now()

	storeMutex.Lock()
	tokens := tokenStore[req.UserID]
	// Update existing token or append
	found := false
	for i, t := range tokens {
		if t.Token == req.Token {
			tokens[i] = req
			found = true
			break
		}
	}
	if !found {
		tokens = append(tokens, req)
	}
	tokenStore[req.UserID] = tokens
	storeMutex.Unlock()

	log.Printf("📱 Registered push token for user %s (%s)", req.UserID, req.Platform)

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	json.NewEncoder(w).Encode(map[string]interface{}{
		"success": true,
		"message": "Token registered successfully",
		"token":   req,
	})
}

// handleSendPush sends a standard push notification (chat message, reaction, ping)
func handleSendPush(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	var payload PushNotificationPayload
	if err := json.NewDecoder(r.Body).Decode(&payload); err != nil {
		http.Error(w, "Invalid request body", http.StatusBadRequest)
		return
	}

	storeMutex.RLock()
	tokens := tokenStore[payload.RecipientID]
	storeMutex.RUnlock()

	log.Printf("🔔 Sending push notification to user %s (Tokens count: %d): [%s] %s",
		payload.RecipientID, len(tokens), payload.Title, payload.Body)

	// Mock FCM/APNs Dispatcher: In production, dispatches via HTTP v1 FCM & APNs HTTP/2
	deliveredCount := len(tokens)
	if deliveredCount == 0 {
		deliveredCount = 1 // Simulated delivered to fallback broadcast channel
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]interface{}{
		"success":        true,
		"recipient_id":   payload.RecipientID,
		"delivered_to":   deliveredCount,
		"is_fcm_v1":      true,
		"is_apns":        true,
		"sent_at":        time.Now().Format(time.RFC3339),
	})
}

// handleSendVoipPush triggers a full-screen CallKit / Telecom VoIP push
func handleSendVoipPush(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	var callPayload VoipCallPushPayload
	if err := json.NewDecoder(r.Body).Decode(&callPayload); err != nil {
		http.Error(w, "Invalid request body", http.StatusBadRequest)
		return
	}

	storeMutex.RLock()
	tokens := tokenStore[callPayload.RecipientID]
	storeMutex.RUnlock()

	log.Printf("📞 [VoIP Push] Incoming %s call from %s (%s) to user %s (CallID: %s)",
		callPayload.CallType, callPayload.CallerName, callPayload.CallerPin, callPayload.RecipientID, callPayload.CallID)

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]interface{}{
		"success":        true,
		"call_id":        callPayload.CallID,
		"recipient_id":   callPayload.RecipientID,
		"call_type":      callPayload.CallType,
		"caller_name":    callPayload.CallerName,
		"is_high_priority": true,
		"wake_lock":      true,
		"delivered_at":   time.Now().Format(time.RFC3339),
	})
}

// handleGetConfig returns push notification channel and server config
func handleGetConfig(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]interface{}{
		"service":          "gochat-notification",
		"fcm_enabled":      true,
		"apns_enabled":     true,
		"voip_supported":   true,
		"channels": []map[string]string{
			{"id": "gochat_messages", "name": "Direct & Group Messages", "importance": "high"},
			{"id": "gochat_calls", "name": "Incoming VoIP Calls", "importance": "max"},
			{"id": "gochat_pings", "name": "GOCHAT PING! Nudges", "importance": "high"},
		},
	})
}
