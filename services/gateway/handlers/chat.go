package handlers

import (
	"net/http"
	"strconv"

	"github.com/gin-gonic/gin"
	"go.uber.org/zap"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"

	chatpb "gochat/gen/chat"
)

// ChatHandler wraps the Chat Service gRPC client.
type ChatHandler struct {
	client chatpb.ChatServiceClient
	log    *zap.Logger
}

// NewChatHandler constructs the ChatHandler.
func NewChatHandler(client chatpb.ChatServiceClient, log *zap.Logger) *ChatHandler {
	return &ChatHandler{client: client, log: log}
}

// CreateConversation handles 1:1 and group creation.
func (h *ChatHandler) CreateConversation(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}

	var req struct {
		Type      int32    `json:"type"` // 0 = direct, 1 = group
		Name      string   `json:"name"`
		MemberIds []string `json:"member_ids"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	// Always ensure the creator is part of the member list
	req.MemberIds = append(req.MemberIds, userID)

	resp, err := h.client.CreateConversation(c.Request.Context(), &chatpb.CreateConversationRequest{
		Type:      chatpb.ConversationType(req.Type),
		Name:      req.Name,
		MemberIds: req.MemberIds,
		CreatorId: userID,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to create conversation")
		return
	}

	c.JSON(http.StatusCreated, resp.Conversation)
}

// GetConversations retrieves the conversation list for the active user.
func (h *ChatHandler) GetConversations(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}

	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	pageSize, _ := strconv.Atoi(c.DefaultQuery("page_size", "30"))

	resp, err := h.client.GetConversations(c.Request.Context(), &chatpb.GetConversationsRequest{
		UserId:   userID,
		Page:     int32(page),
		PageSize: int32(pageSize),
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to fetch conversations")
		return
	}

	c.JSON(http.StatusOK, resp)
}

// GetConversation gets a conversation by its ID.
func (h *ChatHandler) GetConversation(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	convID := c.Param("id")

	resp, err := h.client.GetConversation(c.Request.Context(), &chatpb.GetConversationRequest{
		ConversationId: convID,
		UserId:         userID,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to fetch conversation")
		return
	}

	c.JSON(http.StatusOK, resp.Conversation)
}

// AddMember adds a user to a group conversation.
func (h *ChatHandler) AddMember(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	convID := c.Param("id")

	var req struct {
		NewMemberId string `json:"new_member_id" binding:"required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	resp, err := h.client.AddMember(c.Request.Context(), &chatpb.AddMemberRequest{
		ConversationId: convID,
		RequesterId:    userID,
		NewMemberId:   req.NewMemberId,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to add member")
		return
	}

	c.JSON(http.StatusOK, gin.H{"success": resp.Success})
}

// RemoveMember removes a user from a group conversation.
func (h *ChatHandler) RemoveMember(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	convID := c.Param("id")

	var req struct {
		MemberId string `json:"member_id" binding:"required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	resp, err := h.client.RemoveMember(c.Request.Context(), &chatpb.RemoveMemberRequest{
		ConversationId: convID,
		RequesterId:    userID,
		MemberId:       req.MemberId,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to remove member")
		return
	}

	c.JSON(http.StatusOK, gin.H{"success": resp.Success})
}

// SendMessage delivers a new real-time message.
func (h *ChatHandler) SendMessage(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	convID := c.Param("id")

	var req struct {
		Content   string `json:"content"`
		Type      int32  `json:"type"` // 0=text, 1=image, etc.
		MediaURL  string `json:"media_url"`
		MediaMime string `json:"media_mime"`
		MediaSize int64  `json:"media_size"`
		ParentID  string `json:"parent_id"`  // threaded reply
		ExpiresAt int64  `json:"expires_at"` // TTL self-destruct (Unix ts, 0 = never)
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	resp, err := h.client.SendMessage(c.Request.Context(), &chatpb.SendMessageRequest{
		ConversationId: convID,
		SenderId:       userID,
		Content:        req.Content,
		Type:           chatpb.MessageType(req.Type),
		MediaUrl:       req.MediaURL,
		MediaMime:      req.MediaMime,
		MediaSize:      req.MediaSize,
		ParentId:       req.ParentID,
		ExpiresAt:      req.ExpiresAt,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to send message")
		return
	}

	c.JSON(http.StatusCreated, resp.Message)
}

// ScheduleMessage queues a message for delivery in the future.
func (h *ChatHandler) ScheduleMessage(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	convID := c.Param("id")

	var req struct {
		Content   string `json:"content" binding:"required"`
		Type      int32  `json:"type"`
		MediaURL  string `json:"media_url"`
		SendAt    int64  `json:"send_at" binding:"required"`   // Unix ts
		ExpiresAt int64  `json:"expires_at"` // TTL self-destruct
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	resp, err := h.client.ScheduleMessage(c.Request.Context(), &chatpb.ScheduleMessageRequest{
		ConversationId: convID,
		SenderId:       userID,
		Content:        req.Content,
		Type:           chatpb.MessageType(req.Type),
		MediaUrl:       req.MediaURL,
		SendAt:         req.SendAt,
		ExpiresAt:      req.ExpiresAt,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to schedule message")
		return
	}

	c.JSON(http.StatusCreated, resp.Message)
}

// GetMessages retrieves a conversation's messages, supporting cursor pagination.
func (h *ChatHandler) GetMessages(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	convID := c.Param("id")
	cursor := c.Query("cursor")
	limit, _ := strconv.Atoi(c.DefaultQuery("limit", "50"))

	resp, err := h.client.GetMessages(c.Request.Context(), &chatpb.GetMessagesRequest{
		ConversationId: convID,
		UserId:         userID,
		Cursor:         cursor,
		Limit:          int32(limit),
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to fetch messages")
		return
	}

	c.JSON(http.StatusOK, resp)
}

// GetThread gets replies under a parent message.
func (h *ChatHandler) GetThread(c *gin.Context) {
	parentID := c.Param("parent_id")
	cursor := c.Query("cursor")
	limit, _ := strconv.Atoi(c.DefaultQuery("limit", "50"))

	resp, err := h.client.GetThread(c.Request.Context(), &chatpb.GetThreadRequest{
		ParentMessageId: parentID,
		Cursor:          cursor,
		Limit:           int32(limit),
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to fetch thread")
		return
	}

	c.JSON(http.StatusOK, resp)
}

// EditMessage edits the text of a message.
func (h *ChatHandler) EditMessage(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	msgID := c.Param("id")

	var req struct {
		Content string `json:"content" binding:"required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	resp, err := h.client.EditMessage(c.Request.Context(), &chatpb.EditMessageRequest{
		MessageId: msgID,
		EditorId:  userID,
		Content:   req.Content,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to edit message")
		return
	}

	c.JSON(http.StatusOK, resp)
}

// DeleteMessage performs a soft deletion of a message.
func (h *ChatHandler) DeleteMessage(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	msgID := c.Param("id")

	resp, err := h.client.DeleteMessage(c.Request.Context(), &chatpb.DeleteMessageRequest{
		MessageId: msgID,
		DeleterId: userID,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to delete message")
		return
	}

	c.JSON(http.StatusOK, gin.H{"success": resp.Success})
}

// AddReaction adds an emoji reaction to a message.
func (h *ChatHandler) AddReaction(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	msgID := c.Param("id")

	var req struct {
		Emoji string `json:"emoji" binding:"required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	resp, err := h.client.AddReaction(c.Request.Context(), &chatpb.AddReactionRequest{
		MessageId: msgID,
		UserId:    userID,
		Emoji:     req.Emoji,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to react")
		return
	}

	c.JSON(http.StatusOK, resp.Reaction)
}

// RemoveReaction removes an emoji reaction from a message.
func (h *ChatHandler) RemoveReaction(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	msgID := c.Param("id")

	var req struct {
		Emoji string `json:"emoji" binding:"required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	resp, err := h.client.RemoveReaction(c.Request.Context(), &chatpb.RemoveReactionRequest{
		MessageId: msgID,
		UserId:    userID,
		Emoji:     req.Emoji,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to remove reaction")
		return
	}

	c.JSON(http.StatusOK, gin.H{"success": resp.Success})
}

// MarkRead handles batch read status confirmation.
func (h *ChatHandler) MarkRead(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	convID := c.Param("id")

	var req struct {
		MessageIds []string `json:"message_ids" binding:"required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	resp, err := h.client.MarkRead(c.Request.Context(), &chatpb.MarkReadRequest{
		ConversationId: convID,
		UserId:         userID,
		MessageIds:     req.MessageIds,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to mark as read")
		return
	}

	c.JSON(http.StatusOK, gin.H{"success": resp.Success})
}

// PinMessage pins a message inside its conversation.
func (h *ChatHandler) PinMessage(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	msgID := c.Param("id")

	var req struct {
		ConversationId string `json:"conversation_id" binding:"required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	resp, err := h.client.PinMessage(c.Request.Context(), &chatpb.PinMessageRequest{
		MessageId:      msgID,
		ConversationId: req.ConversationId,
		UserId:         userID,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to pin message")
		return
	}

	c.JSON(http.StatusOK, gin.H{"success": resp.Success})
}

// UnpinMessage removes a message from a conversation's pins.
func (h *ChatHandler) UnpinMessage(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	msgID := c.Param("id")

	var req struct {
		ConversationId string `json:"conversation_id" binding:"required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	resp, err := h.client.UnpinMessage(c.Request.Context(), &chatpb.UnpinMessageRequest{
		MessageId:      msgID,
		ConversationId: req.ConversationId,
		UserId:         userID,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to unpin message")
		return
	}

	c.JSON(http.StatusOK, gin.H{"success": resp.Success})
}

// SearchMessages performs full-text queries across conversation messages.
func (h *ChatHandler) SearchMessages(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	query := c.Query("query")
	convID := c.Query("conversation_id")
	limit, _ := strconv.Atoi(c.DefaultQuery("limit", "20"))
	offset, _ := strconv.Atoi(c.DefaultQuery("offset", "0"))

	if query == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "query parameter is required"})
		return
	}

	resp, err := h.client.SearchMessages(c.Request.Context(), &chatpb.SearchMessagesRequest{
		UserId:         userID,
		Query:          query,
		ConversationId: convID,
		Limit:          int32(limit),
		Offset:         int32(offset),
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to search messages")
		return
	}

	c.JSON(http.StatusOK, resp)
}

// GetUnreadCounts gathers unread totals for each conversation.
func (h *ChatHandler) GetUnreadCounts(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}

	resp, err := h.client.GetUnreadCounts(c.Request.Context(), &chatpb.GetUnreadCountsRequest{
		UserId: userID,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to fetch unread counts")
		return
	}

	c.JSON(http.StatusOK, resp.Counts)
}

// SendTypingIndicator alerts typing status in a conversation.
func (h *ChatHandler) SendTypingIndicator(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	convID := c.Param("id")

	var req struct {
		IsTyping bool `json:"is_typing"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	resp, err := h.client.SendTypingIndicator(c.Request.Context(), &chatpb.SendTypingIndicatorRequest{
		ConversationId: convID,
		UserId:         userID,
		IsTyping:       req.IsTyping,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to send typing indicator")
		return
	}

	c.JSON(http.StatusOK, gin.H{"success": resp.Success})
}

// handleGrpcError converts gRPC errors into standard HTTP status codes.
func (h *ChatHandler) handleGrpcError(c *gin.Context, err error, actionMsg string) {
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
	case codes.AlreadyExists:
		c.JSON(http.StatusConflict, gin.H{"error": st.Message()})
	default:
		c.JSON(http.StatusInternalServerError, gin.H{"error": st.Message()})
	}
}
