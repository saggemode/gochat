package handlers

import (
	"net/http"
	"strconv"

	"github.com/gin-gonic/gin"
	"go.uber.org/zap"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"

	callpb "gochat/gen/call"
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
