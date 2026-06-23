package handlers

import (
	"net/http"

	aipb "gochat/gen/ai"

	"github.com/gin-gonic/gin"
	"go.uber.org/zap"
)

// AIHandler routes HTTP requests to the AI gRPC service.
type AIHandler struct {
	client aipb.AIServiceClient
	log    *zap.Logger
}

// NewAIHandler constructs the handler.
func NewAIHandler(client aipb.AIServiceClient, log *zap.Logger) *AIHandler {
	return &AIHandler{client: client, log: log}
}

// SummarizeChat handles POST /api/ai/summarize
func (h *AIHandler) SummarizeChat(c *gin.Context) {
	userID := c.GetString("user_id")
	var req struct {
		ConversationID string `json:"conversation_id" binding:"required"`
		MessageCount   int32  `json:"message_count"`
		Language       string `json:"language"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	resp, err := h.client.SummarizeChat(c.Request.Context(), &aipb.SummarizeChatRequest{
		UserId:         userID,
		ConversationId: req.ConversationID,
		MessageCount:   req.MessageCount,
		Language:       req.Language,
	})
	if err != nil {
		h.log.Error("SummarizeChat failed", zap.Error(err))
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to summarize"})
		return
	}

	c.JSON(http.StatusOK, resp)
}

// SuggestReplies handles POST /api/ai/suggest
func (h *AIHandler) SuggestReplies(c *gin.Context) {
	userID := c.GetString("user_id")
	var req struct {
		ConversationID string `json:"conversation_id" binding:"required"`
		Count          int32  `json:"count"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	resp, err := h.client.SuggestReplies(c.Request.Context(), &aipb.SuggestRepliesRequest{
		UserId:         userID,
		ConversationId: req.ConversationID,
		Count:          req.Count,
	})
	if err != nil {
		h.log.Error("SuggestReplies failed", zap.Error(err))
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to suggest replies"})
		return
	}

	c.JSON(http.StatusOK, resp)
}

// TranslateMessage handles POST /api/ai/translate
func (h *AIHandler) TranslateMessage(c *gin.Context) {
	userID := c.GetString("user_id")
	var req struct {
		Text           string `json:"text" binding:"required"`
		SourceLanguage string `json:"source_language"`
		TargetLanguage string `json:"target_language" binding:"required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	resp, err := h.client.TranslateMessage(c.Request.Context(), &aipb.TranslateMessageRequest{
		UserId:         userID,
		Text:           req.Text,
		SourceLanguage: req.SourceLanguage,
		TargetLanguage: req.TargetLanguage,
	})
	if err != nil {
		h.log.Error("TranslateMessage failed", zap.Error(err))
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to translate"})
		return
	}

	c.JSON(http.StatusOK, resp)
}

// AdjustTone handles POST /api/ai/tone
func (h *AIHandler) AdjustTone(c *gin.Context) {
	userID := c.GetString("user_id")
	var req struct {
		Text string `json:"text" binding:"required"`
		Tone string `json:"tone" binding:"required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	resp, err := h.client.AdjustTone(c.Request.Context(), &aipb.AdjustToneRequest{
		UserId: userID,
		Text:   req.Text,
		Tone:   req.Tone,
	})
	if err != nil {
		h.log.Error("AdjustTone failed", zap.Error(err))
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to adjust tone"})
		return
	}

	c.JSON(http.StatusOK, resp)
}

// ExtractActionItems handles POST /api/ai/actions
func (h *AIHandler) ExtractActionItems(c *gin.Context) {
	userID := c.GetString("user_id")
	var req struct {
		ConversationID string `json:"conversation_id" binding:"required"`
		MessageCount   int32  `json:"message_count"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	resp, err := h.client.ExtractActionItems(c.Request.Context(), &aipb.ExtractActionItemsRequest{
		UserId:         userID,
		ConversationId: req.ConversationID,
		MessageCount:   req.MessageCount,
	})
	if err != nil {
		h.log.Error("ExtractActionItems failed", zap.Error(err))
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to extract actions"})
		return
	}

	c.JSON(http.StatusOK, resp)
}
