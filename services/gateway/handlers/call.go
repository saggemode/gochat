package handlers

import (
	"context"
	"net/http"
	"os"
	"strconv"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"go.uber.org/zap"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"

	callpb "gochat/gen/call"
	"gochat/pkg/fcm"
)

type CallHandler struct {
	client callpb.CallServiceClient
	log    *zap.Logger
}

func NewCallHandler(client callpb.CallServiceClient, log *zap.Logger) *CallHandler {
	return &CallHandler{client: client, log: log}
}

func (h *CallHandler) StartCall(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}

	var req struct {
		ReceiverId string `json:"receiver_id"`
		Type       string `json:"type"` // "voice" or "video"
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid json payload"})
		return
	}

	if req.ReceiverId == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "receiver_id is required"})
		return
	}

	callType := callpb.CallType_VOICE
	if req.Type == "video" {
		callType = callpb.CallType_VIDEO
	}

	resp, err := h.client.StartCall(c.Request.Context(), &callpb.StartCallRequest{
		CallerId:   userID,
		ReceiverId: req.ReceiverId,
		Type:       callType,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to initiate call")
		return
	}

	// Dispatch high-priority incoming call notification to receiver
	go func() {
		pushCtx, pCancel := context.WithTimeout(context.Background(), 8*time.Second)
		defer pCancel()

		callTitle := "Incoming Voice Call"
		if req.Type == "video" {
			callTitle = "Incoming Video Call"
		}
		callID := ""
		if resp != nil && resp.Call != nil {
			callID = resp.Call.Id
		}
		pushData := map[string]string{
			"type":        "incoming_call",
			"call_id":     callID,
			"caller_id":   userID,
			"call_type":   req.Type,
			"title":       callTitle,
			"body":        "Incoming call on GoChat",
		}
		_ = fcm.SendToUser(pushCtx, req.ReceiverId, callTitle, "Incoming call", pushData)
	}()

	c.JSON(http.StatusCreated, resp.Call)
}

func (h *CallHandler) AcceptCall(c *gin.Context) {
	callID := c.Param("id")
	userID := getUserID(c)
	if userID == "" {
		return
	}

	resp, err := h.client.AcceptCall(c.Request.Context(), &callpb.AcceptCallRequest{
		CallId:     callID,
		ReceiverId: userID,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to accept call")
		return
	}

	c.JSON(http.StatusOK, resp.Call)
}

func (h *CallHandler) RejectCall(c *gin.Context) {
	callID := c.Param("id")
	userID := getUserID(c)
	if userID == "" {
		return
	}

	var req struct {
		IsBusy bool `json:"is_busy"`
	}
	// is_busy is optional, ignore error if missing
	_ = c.ShouldBindJSON(&req)

	resp, err := h.client.RejectCall(c.Request.Context(), &callpb.RejectCallRequest{
		CallId:     callID,
		ReceiverId: userID,
		IsBusy:     req.IsBusy,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to reject call")
		return
	}

	c.JSON(http.StatusOK, resp.Call)
}

func (h *CallHandler) EndCall(c *gin.Context) {
	callID := c.Param("id")
	userID := getUserID(c)
	if userID == "" {
		return
	}

	resp, err := h.client.EndCall(c.Request.Context(), &callpb.EndCallRequest{
		CallId: callID,
		UserId: userID,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to end call")
		return
	}

	c.JSON(http.StatusOK, resp.Call)
}

func (h *CallHandler) SendSignalingMessage(c *gin.Context) {
	callID := c.Param("id")
	userID := getUserID(c)
	if userID == "" {
		return
	}

	var req struct {
		ReceiverId string `json:"receiver_id"`
		Type       string `json:"type"` // "offer", "answer", "ice-candidate"
		Sdp        string `json:"sdp"`
		Candidate  string `json:"candidate"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid json payload"})
		return
	}

	if req.ReceiverId == "" || req.Type == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "receiver_id and type are required"})
		return
	}

	resp, err := h.client.SendSignalingMessage(c.Request.Context(), &callpb.SendSignalingMessageRequest{
		CallId:     callID,
		SenderId:   userID,
		ReceiverId: req.ReceiverId,
		Type:       req.Type,
		Sdp:        req.Sdp,
		Candidate:  req.Candidate,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to send WebRTC signaling message")
		return
	}

	c.JSON(http.StatusOK, gin.H{"success": resp.Success})
}

// GetIceServers returns the STUN/TURN servers the WebRTC clients should use.
// TURN (required for calls between two phones on mobile/carrier NAT) is read from
// env so a coturn/Twilio/Metered server can be configured without a code change:
//   TURN_URLS=turn:turn.example.com:3478,turns:turn.example.com:5349
//   TURN_USERNAME=...   TURN_PASSWORD=...
// STUN_URLS is optional and defaults to Google's public STUN servers.
func (h *CallHandler) GetIceServers(c *gin.Context) {
	iceServers := make([]gin.H, 0, 2)

	stun := os.Getenv("STUN_URLS")
	if stun == "" {
		stun = "stun:stun.l.google.com:19302,stun:stun1.l.google.com:19302"
	}
	iceServers = append(iceServers, gin.H{"urls": splitCSV(stun)})

	turnURLs := os.Getenv("TURN_URLS")
	turnUser := os.Getenv("TURN_USERNAME")
	turnPass := os.Getenv("TURN_PASSWORD")
	if turnURLs != "" && turnUser != "" && turnPass != "" {
		iceServers = append(iceServers, gin.H{
			"urls":       splitCSV(turnURLs),
			"username":   turnUser,
			"credential": turnPass,
		})
	}

	c.JSON(http.StatusOK, gin.H{"ice_servers": iceServers})
}

func splitCSV(s string) []string {
	parts := strings.Split(s, ",")
	out := make([]string, 0, len(parts))
	for _, p := range parts {
		if t := strings.TrimSpace(p); t != "" {
			out = append(out, t)
		}
	}
	return out
}

func (h *CallHandler) GetCallHistory(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}

	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	pageSize, _ := strconv.Atoi(c.DefaultQuery("page_size", "20"))

	resp, err := h.client.GetCallHistory(c.Request.Context(), &callpb.GetCallHistoryRequest{
		UserId:   userID,
		Page:     int32(page),
		PageSize: int32(pageSize),
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to fetch call history")
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"calls": resp.Calls,
		"total": resp.Total,
	})
}

func (h *CallHandler) handleGrpcError(c *gin.Context, err error, actionMsg string) {
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
	case codes.PermissionDenied:
		c.JSON(http.StatusForbidden, gin.H{"error": st.Message()})
	default:
		c.JSON(http.StatusInternalServerError, gin.H{"error": st.Message()})
	}
}
