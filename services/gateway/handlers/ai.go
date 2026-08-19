package handlers

import (
	"net/http"

	"github.com/gin-gonic/gin"
	"go.uber.org/zap"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"

	aipb "gochat/gen/ai"
)

// AIHandler wraps the AI Service gRPC client.
type AIHandler struct {
	client aipb.AIServiceClient
	log    *zap.Logger
}

// NewAIHandler constructs the AIHandler.
func NewAIHandler(client aipb.AIServiceClient, log *zap.Logger) *AIHandler {
	return &AIHandler{client: client, log: log}
}

// SummarizeChat handles POST /api/v1/ai/summarize
func (h *AIHandler) SummarizeChat(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}

	var req struct {
		ConversationID string `json:"conversation_id" binding:"required"`
		MessageCount   int32  `json:"message_count"`
		Language       string `json:"language"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	if h.client == nil {
		c.JSON(http.StatusServiceUnavailable, gin.H{"error": "AI service unavailable"})
		return
	}

	resp, err := h.client.SummarizeChat(c.Request.Context(), &aipb.SummarizeChatRequest{
		UserId:         userID,
		ConversationId: req.ConversationID,
		MessageCount:   req.MessageCount,
		Language:       req.Language,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to summarize chat")
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"summary":       resp.Summary,
		"messages_read": resp.MessagesRead,
		"key_topics":    resp.KeyTopics,
	})
}

// SuggestReplies handles POST /api/v1/ai/suggest-replies
func (h *AIHandler) SuggestReplies(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}

	var req struct {
		ConversationID string `json:"conversation_id" binding:"required"`
		Count          int32  `json:"count"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	if h.client == nil {
		c.JSON(http.StatusServiceUnavailable, gin.H{"error": "AI service unavailable"})
		return
	}

	resp, err := h.client.SuggestReplies(c.Request.Context(), &aipb.SuggestRepliesRequest{
		UserId:         userID,
		ConversationId: req.ConversationID,
		Count:          req.Count,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to suggest replies")
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"suggestions": resp.Suggestions,
	})
}

// TranslateMessage handles POST /api/v1/ai/translate
func (h *AIHandler) TranslateMessage(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}

	var req struct {
		Text           string `json:"text" binding:"required"`
		TargetLanguage string `json:"target_language" binding:"required"`
		SourceLanguage string `json:"source_language"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	if h.client == nil {
		c.JSON(http.StatusServiceUnavailable, gin.H{"error": "AI service unavailable"})
		return
	}

	resp, err := h.client.TranslateMessage(c.Request.Context(), &aipb.TranslateMessageRequest{
		UserId:         userID,
		Text:           req.Text,
		TargetLanguage: req.TargetLanguage,
		SourceLanguage: req.SourceLanguage,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to translate message")
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"translated_text":   resp.TranslatedText,
		"detected_language": resp.DetectedLanguage,
	})
}

// AdjustTone handles POST /api/v1/ai/adjust-tone
func (h *AIHandler) AdjustTone(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}

	var req struct {
		Text string `json:"text" binding:"required"`
		Tone string `json:"tone" binding:"required"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	if h.client == nil {
		c.JSON(http.StatusServiceUnavailable, gin.H{"error": "AI service unavailable"})
		return
	}

	resp, err := h.client.AdjustTone(c.Request.Context(), &aipb.AdjustToneRequest{
		UserId: userID,
		Text:   req.Text,
		Tone:   req.Tone,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to adjust tone")
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"adjusted_text": resp.AdjustedText,
		"original_tone": resp.OriginalTone,
	})
}

// ExtractActionItems handles POST /api/v1/ai/action-items
func (h *AIHandler) ExtractActionItems(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}

	var req struct {
		ConversationID string `json:"conversation_id" binding:"required"`
		MessageCount   int32  `json:"message_count"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	if h.client == nil {
		c.JSON(http.StatusServiceUnavailable, gin.H{"error": "AI service unavailable"})
		return
	}

	resp, err := h.client.ExtractActionItems(c.Request.Context(), &aipb.ExtractActionItemsRequest{
		UserId:         userID,
		ConversationId: req.ConversationID,
		MessageCount:   req.MessageCount,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to extract action items")
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"action_items": resp.ActionItems,
		"meetings":     resp.Meetings,
	})
}

func (h *AIHandler) handleGrpcError(c *gin.Context, err error, fallbackMsg string) {
	st, ok := status.FromError(err)
	if !ok {
		h.log.Error(fallbackMsg, zap.Error(err))
		c.JSON(http.StatusInternalServerError, gin.H{"error": fallbackMsg})
		return
	}

	switch st.Code() {
	case codes.InvalidArgument:
		c.JSON(http.StatusBadRequest, gin.H{"error": st.Message()})
	case codes.NotFound:
		c.JSON(http.StatusNotFound, gin.H{"error": st.Message()})
	case codes.PermissionDenied:
		c.JSON(http.StatusForbidden, gin.H{"error": st.Message()})
	case codes.Unauthenticated:
		c.JSON(http.StatusUnauthorized, gin.H{"error": st.Message()})
	case codes.Unavailable:
		c.JSON(http.StatusServiceUnavailable, gin.H{"error": "AI service temporarily unavailable"})
	default:
		h.log.Error(fallbackMsg, zap.String("grpc_code", st.Code().String()), zap.String("grpc_msg", st.Message()))
		c.JSON(http.StatusInternalServerError, gin.H{"error": st.Message()})
	}
}
