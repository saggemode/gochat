package handlers

import (
	"encoding/json"
	"net/http"
	"strconv"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"go.uber.org/zap"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"

	authpb "gochat/gen/auth"
	chatpb "gochat/gen/chat"
	"gochat/services/gateway/ws"
)

// ChatHandler wraps the Chat Service gRPC client.
type ChatHandler struct {
	client     chatpb.ChatServiceClient
	authClient authpb.AuthServiceClient
	hub        *ws.Hub
	log        *zap.Logger
}

// NewChatHandler constructs the ChatHandler.
func NewChatHandler(client chatpb.ChatServiceClient, authClient authpb.AuthServiceClient, hub *ws.Hub, log *zap.Logger) *ChatHandler {
	return &ChatHandler{client: client, authClient: authClient, hub: hub, log: log}
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
		IsGroup   bool     `json:"is_group"`
		IsSecret  bool     `json:"is_secret"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	// Resolve any PINs, emails, phones, or display names in MemberIds to actual UUIDs
	resolvedUUIDs := make([]string, 0, len(req.MemberIds)+1)
	resolvedMap := make(map[string]bool)

	if h.authClient != nil && len(req.MemberIds) > 0 {
		usersResp, err := h.authClient.GetUsers(c.Request.Context(), &authpb.GetUsersRequest{UserIds: req.MemberIds})
		if err == nil && usersResp != nil {
			for _, u := range usersResp.Users {
				if u.Id != "" && !resolvedMap[u.Id] {
					resolvedUUIDs = append(resolvedUUIDs, u.Id)
					resolvedMap[u.Id] = true
				}
			}
		}
	}

	// Fallback for any member_id that is already a valid UUID
	for _, id := range req.MemberIds {
		if _, err := uuid.Parse(id); err == nil {
			if !resolvedMap[id] {
				resolvedUUIDs = append(resolvedUUIDs, id)
				resolvedMap[id] = true
			}
		}
	}

	// Always ensure creator is included
	if !resolvedMap[userID] {
		resolvedUUIDs = append(resolvedUUIDs, userID)
		resolvedMap[userID] = true
	}

	convType := chatpb.ConversationType_DIRECT
	if req.Type == 1 || req.IsGroup {
		convType = chatpb.ConversationType_GROUP
	}

	resp, err := h.client.CreateConversation(c.Request.Context(), &chatpb.CreateConversationRequest{
		Type:      convType,
		Name:      req.Name,
		MemberIds: resolvedUUIDs,
		CreatorId: userID,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to create conversation")
		return
	}

	// For direct 1:1 conversation, populate partner's display name if name is empty
	conv := resp.Conversation
	if conv != nil && (conv.Type == chatpb.ConversationType_DIRECT || len(conv.MemberIds) <= 2) && h.authClient != nil {
		for _, mID := range conv.MemberIds {
			if mID != userID {
				uResp, err := h.authClient.GetUser(c.Request.Context(), &authpb.GetUserRequest{UserId: mID})
				if err == nil && uResp != nil && uResp.User != nil {
					conv.Name = uResp.User.DisplayName
					if conv.Name == "" {
						conv.Name = uResp.User.Email
					}
					if conv.Name == "" {
						conv.Name = uResp.User.Phone
					}
				}
				break
			}
		}
	}

	c.JSON(http.StatusCreated, conv)
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

	// Populate partner names for 1:1 direct conversations
	if resp != nil && len(resp.Conversations) > 0 && h.authClient != nil {
		partnerIDs := make([]string, 0)
		for _, conv := range resp.Conversations {
			for _, mID := range conv.MemberIds {
				if mID != userID {
					partnerIDs = append(partnerIDs, mID)
				}
			}
		}

		if len(partnerIDs) > 0 {
			usersResp, err := h.authClient.GetUsers(c.Request.Context(), &authpb.GetUsersRequest{UserIds: partnerIDs})
			if err == nil && usersResp != nil {
				userMap := make(map[string]string)
				for _, u := range usersResp.Users {
					name := u.DisplayName
					if name == "" {
						name = u.Email
					}
					if name == "" {
						name = u.Phone
					}
					userMap[u.Id] = name
				}

				for _, conv := range resp.Conversations {
					if conv.Type == chatpb.ConversationType_DIRECT || conv.Name == "" {
						for _, mID := range conv.MemberIds {
							if mID != userID {
								if partnerName, ok := userMap[mID]; ok && partnerName != "" {
									conv.Name = partnerName
								}
								break
							}
						}
					}
				}
			}
		}
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
		NewMemberId:    req.NewMemberId,
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
		Content          string   `json:"content"`
		Type             int32    `json:"type"` // 0=text, 1=image, etc.
		MediaURL         string   `json:"media_url"`
		MediaMime        string   `json:"media_mime"`
		MediaSize        int64    `json:"media_size"`
		ParentID         string   `json:"parent_id"`  // threaded reply
		ExpiresAt        int64    `json:"expires_at"` // TTL self-destruct (Unix ts, 0 = never)
		MentionedUserIds []string `json:"mentioned_user_ids"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	resp, err := h.client.SendMessage(c.Request.Context(), &chatpb.SendMessageRequest{
		ConversationId:   convID,
		SenderId:         userID,
		Content:          req.Content,
		Type:             chatpb.MessageType(req.Type),
		MediaUrl:         req.MediaURL,
		MediaMime:        req.MediaMime,
		MediaSize:        req.MediaSize,
		ParentId:         req.ParentID,
		ExpiresAt:        req.ExpiresAt,
		MentionedUserIds: req.MentionedUserIds,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to send message")
		return
	}

	// Fan out to all conversation members connected via WebSocket immediately
	if h.hub != nil && resp != nil && resp.Message != nil {
		go func(m *chatpb.Message, cid, uid string) {
			convResp, cErr := h.client.GetConversation(c.Request.Context(), &chatpb.GetConversationRequest{
				ConversationId: cid,
				UserId:         uid,
			})
			if cErr == nil && convResp != nil && convResp.Conversation != nil {
				payloadMap := map[string]interface{}{
					"type":            "new_message",
					"event_type":      "EVENT_NEW_MESSAGE",
					"conversation_id": cid,
					"sender_id":       uid,
					"message": map[string]interface{}{
						"id":              m.Id,
						"conversation_id": m.ConversationId,
						"sender_id":       m.SenderId,
						"content":         m.Content,
						"type":            m.Type.String(),
						"media_type":      m.Type.String(),
						"status":          m.Status.String(),
						"media_url":       m.MediaUrl,
						"media_mime":      m.MediaMime,
						"media_size":      m.MediaSize,
						"parent_id":       m.ParentId,
						"created_at":      m.CreatedAt,
						"send_at":         m.SendAt,
					},
				}
				data, _ := json.Marshal(payloadMap)
				for _, memberID := range convResp.Conversation.MemberIds {
					if memberID != uid {
						h.hub.SendToUser(memberID, data)
					}
				}
			}
		}(resp.Message, convID, userID)
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
		SendAt    int64  `json:"send_at" binding:"required"` // Unix ts
		ExpiresAt int64  `json:"expires_at"`                 // TTL self-destruct
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
		MessageIds []string `json:"message_ids"`
	}
	// Body is optional when marking an entire conversation read
	_ = c.ShouldBindJSON(&req)

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

// SearchMessages performs full-text and fuzzy queries across conversation messages.
func (h *ChatHandler) SearchMessages(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	query := c.Query("query")
	convID := c.Query("conversation_id")
	senderName := c.Query("sender_name")
	mediaType := c.Query("media_type")
	limit, _ := strconv.Atoi(c.DefaultQuery("limit", "20"))
	offset, _ := strconv.Atoi(c.DefaultQuery("offset", "0"))

	if query == "" && convID == "" && senderName == "" && mediaType == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "at least one search filter (query, conversation_id, sender_name, or media_type) is required"})
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

// ForwardMessage forwards a message to another conversation.
func (h *ChatHandler) ForwardMessage(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	msgID := c.Param("id")

	var req struct {
		TargetConversationId string `json:"target_conversation_id" binding:"required"`
		Content              string `json:"content"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	resp, err := h.client.ForwardMessage(c.Request.Context(), &chatpb.ForwardMessageRequest{
		MessageId:            msgID,
		SenderId:             userID,
		TargetConversationId: req.TargetConversationId,
		Content:              req.Content,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to forward message")
		return
	}

	c.JSON(http.StatusCreated, resp.Message)
}

// ── Chat Folders ─────────────────────────────────────────────────────────────

func (h *ChatHandler) CreateFolder(c *gin.Context) {
	userID := c.GetString("user_id")
	if userID == "" {
		return
	}
	var req struct {
		Name      string `json:"name" binding:"required"`
		Icon      string `json:"icon"`
		Color     string `json:"color"`
		SortOrder int32  `json:"sort_order"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	resp, err := h.client.CreateFolder(c.Request.Context(), &chatpb.CreateFolderRequest{
		UserId:    userID,
		Name:      req.Name,
		Icon:      req.Icon,
		Color:     req.Color,
		SortOrder: req.SortOrder,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to create folder")
		return
	}
	c.JSON(http.StatusCreated, resp.Folder)
}

func (h *ChatHandler) DeleteFolder(c *gin.Context) {
	userID := c.GetString("user_id")
	if userID == "" {
		return
	}
	folderID := c.Param("id")
	resp, err := h.client.DeleteFolder(c.Request.Context(), &chatpb.DeleteFolderRequest{
		UserId:   userID,
		FolderId: folderID,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to delete folder")
		return
	}
	c.JSON(http.StatusOK, gin.H{"success": resp.Success})
}

func (h *ChatHandler) ListFolders(c *gin.Context) {
	userID := c.GetString("user_id")
	if userID == "" {
		return
	}
	resp, err := h.client.ListFolders(c.Request.Context(), &chatpb.ListFoldersRequest{
		UserId: userID,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to list folders")
		return
	}
	c.JSON(http.StatusOK, resp.Folders)
}

func (h *ChatHandler) AddToFolder(c *gin.Context) {
	userID := c.GetString("user_id")
	if userID == "" {
		return
	}
	folderID := c.Param("id")
	var req struct {
		ConversationID string `json:"conversation_id" binding:"required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	resp, err := h.client.AddToFolder(c.Request.Context(), &chatpb.AddToFolderRequest{
		UserId:         userID,
		FolderId:       folderID,
		ConversationId: req.ConversationID,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to add conversation to folder")
		return
	}
	c.JSON(http.StatusOK, gin.H{"success": resp.Success})
}

func (h *ChatHandler) RemoveFromFolder(c *gin.Context) {
	userID := c.GetString("user_id")
	if userID == "" {
		return
	}
	folderID := c.Param("id")
	var req struct {
		ConversationID string `json:"conversation_id" binding:"required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	resp, err := h.client.RemoveFromFolder(c.Request.Context(), &chatpb.RemoveFromFolderRequest{
		UserId:         userID,
		FolderId:       folderID,
		ConversationId: req.ConversationID,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to remove conversation from folder")
		return
	}
	c.JSON(http.StatusOK, gin.H{"success": resp.Success})
}

// ── Chat Labels ──────────────────────────────────────────────────────────────

func (h *ChatHandler) AddLabel(c *gin.Context) {
	userID := c.GetString("user_id")
	if userID == "" {
		return
	}
	var req struct {
		MessageID string `json:"message_id" binding:"required"`
		Label     string `json:"label" binding:"required"`
		Color     string `json:"color"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	resp, err := h.client.AddLabel(c.Request.Context(), &chatpb.AddLabelRequest{
		UserId:    userID,
		MessageId: req.MessageID,
		Label:     req.Label,
		Color:     req.Color,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to add label")
		return
	}
	c.JSON(http.StatusCreated, resp.ChatLabel)
}

func (h *ChatHandler) RemoveLabel(c *gin.Context) {
	userID := c.GetString("user_id")
	if userID == "" {
		return
	}
	var req struct {
		MessageID string `json:"message_id" binding:"required"`
		Label     string `json:"label" binding:"required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	resp, err := h.client.RemoveLabel(c.Request.Context(), &chatpb.RemoveLabelRequest{
		UserId:    userID,
		MessageId: req.MessageID,
		Label:     req.Label,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to remove label")
		return
	}
	c.JSON(http.StatusOK, gin.H{"success": resp.Success})
}

func (h *ChatHandler) ListLabels(c *gin.Context) {
	userID := c.GetString("user_id")
	if userID == "" {
		return
	}
	label := c.Query("label")
	resp, err := h.client.ListLabels(c.Request.Context(), &chatpb.ListLabelsRequest{
		UserId: userID,
		Label:  label,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to list labels")
		return
	}
	c.JSON(http.StatusOK, resp.Labels)
}

// ── Chat Analytics ───────────────────────────────────────────────────────────

func (h *ChatHandler) GetChatAnalytics(c *gin.Context) {
	userID := c.GetString("user_id")
	if userID == "" {
		return
	}
	convID := c.Query("conversation_id")
	resp, err := h.client.GetChatAnalytics(c.Request.Context(), &chatpb.GetChatAnalyticsRequest{
		UserId:         userID,
		ConversationId: convID,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to fetch analytics")
		return
	}
	c.JSON(http.StatusOK, resp.Analytics)
}

// ── Notification Profiles ────────────────────────────────────────────────────

func (h *ChatHandler) SetNotificationProfile(c *gin.Context) {
	userID := c.GetString("user_id")
	if userID == "" {
		return
	}
	var req struct {
		ConversationID      string `json:"conversation_id" binding:"required"`
		Muted               bool   `json:"muted"`
		MuteUntil           int64  `json:"mute_until"`
		Sound               string `json:"sound"`
		Vibration           bool   `json:"vibration"`
		Priority            string `json:"priority"`
		ShowPreview         bool   `json:"show_preview"`
		NotifyOnMentionOnly bool   `json:"notify_on_mention_only"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	resp, err := h.client.SetNotificationProfile(c.Request.Context(), &chatpb.SetNotificationProfileRequest{
		UserId: userID,
		Profile: &chatpb.NotificationProfile{
			ConversationId:      req.ConversationID,
			Muted:               req.Muted,
			MuteUntil:           req.MuteUntil,
			Sound:               req.Sound,
			Vibration:           req.Vibration,
			Priority:            req.Priority,
			ShowPreview:         req.ShowPreview,
			NotifyOnMentionOnly: req.NotifyOnMentionOnly,
		},
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to set notification profile")
		return
	}
	c.JSON(http.StatusOK, gin.H{"success": resp.Success})
}

func (h *ChatHandler) GetNotificationProfiles(c *gin.Context) {
	userID := c.GetString("user_id")
	if userID == "" {
		return
	}
	resp, err := h.client.GetNotificationProfiles(c.Request.Context(), &chatpb.GetNotificationProfilesRequest{
		UserId: userID,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to list notification profiles")
		return
	}
	c.JSON(http.StatusOK, resp.Profiles)
}

// ── Polls ────────────────────────────────────────────────────────────────────

func (h *ChatHandler) CreatePoll(c *gin.Context) {
	userID := c.GetString("user_id")
	if userID == "" {
		return
	}
	convID := c.Param("id")
	var req struct {
		Question    string   `json:"question" binding:"required"`
		Options     []string `json:"options" binding:"required,min=2,max=10"`
		IsAnonymous bool     `json:"is_anonymous"`
		IsMultiple  bool     `json:"is_multiple"`
		ExpiresAt   int64    `json:"expires_at"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	resp, err := h.client.CreatePoll(c.Request.Context(), &chatpb.CreatePollRequest{
		ConversationId: convID,
		CreatedBy:      userID,
		Question:       req.Question,
		Options:        req.Options,
		IsAnonymous:    req.IsAnonymous,
		IsMultiple:     req.IsMultiple,
		ExpiresAt:      req.ExpiresAt,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to create poll")
		return
	}
	c.JSON(http.StatusCreated, resp.Poll)
}

func (h *ChatHandler) GetPoll(c *gin.Context) {
	userID := c.GetString("user_id")
	if userID == "" {
		return
	}
	pollID := c.Param("id")
	resp, err := h.client.GetPoll(c.Request.Context(), &chatpb.GetPollRequest{
		PollId: pollID,
		UserId: userID,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to fetch poll")
		return
	}
	c.JSON(http.StatusOK, resp.Poll)
}

func (h *ChatHandler) VotePoll(c *gin.Context) {
	userID := c.GetString("user_id")
	if userID == "" {
		return
	}
	pollID := c.Param("id")
	var req struct {
		OptionIDs []string `json:"option_ids" binding:"required,min=1"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	resp, err := h.client.VotePoll(c.Request.Context(), &chatpb.VotePollRequest{
		PollId:    pollID,
		UserId:    userID,
		OptionIds: req.OptionIDs,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to vote in poll")
		return
	}
	c.JSON(http.StatusOK, resp.Poll)
}

func (h *ChatHandler) ClosePoll(c *gin.Context) {
	userID := c.GetString("user_id")
	if userID == "" {
		return
	}
	pollID := c.Param("id")
	resp, err := h.client.ClosePoll(c.Request.Context(), &chatpb.ClosePollRequest{
		PollId: pollID,
		UserId: userID,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to close poll")
		return
	}
	c.JSON(http.StatusOK, resp.Poll)
}

// SuggestReplies provides quick AI smart reply suggestions for a conversation.
func (h *ChatHandler) SuggestReplies(c *gin.Context) {
	suggestions := []string{
		"Sounds good!",
		"Okay, thanks!",
		"Let me check and get back to you!",
	}
	c.JSON(http.StatusOK, gin.H{"suggestions": suggestions})
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
