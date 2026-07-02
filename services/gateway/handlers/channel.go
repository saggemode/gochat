package handlers

import (
	"net/http"
	"strconv"

	"github.com/gin-gonic/gin"
	"go.uber.org/zap"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"

	channelpb "gochat/gen/channel"
)

type ChannelHandler struct {
	client channelpb.ChannelServiceClient
	log    *zap.Logger
}

func NewChannelHandler(client channelpb.ChannelServiceClient, log *zap.Logger) *ChannelHandler {
	return &ChannelHandler{client: client, log: log}
}

func (h *ChannelHandler) CreateChannel(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	var req struct {
		Name        string `json:"name"`
		Description string `json:"description"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid json payload"})
		return
	}

	resp, err := h.client.CreateChannel(c.Request.Context(), &channelpb.CreateChannelRequest{
		Name:        req.Name,
		Description: req.Description,
		CreatorId:   userID,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to create channel")
		return
	}

	c.JSON(http.StatusCreated, resp.Channel)
}

func (h *ChannelHandler) DeleteChannel(c *gin.Context) {
	channelID := c.Param("id")
	userID := getUserID(c)
	if userID == "" {
		return
	}

	resp, err := h.client.DeleteChannel(c.Request.Context(), &channelpb.DeleteChannelRequest{
		Id:      channelID,
		OwnerId: userID,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to delete channel")
		return
	}

	c.JSON(http.StatusOK, gin.H{"success": resp.Success})
}

func (h *ChannelHandler) SubscribeChannel(c *gin.Context) {
	channelID := c.Param("id")
	userID := getUserID(c)
	if userID == "" {
		return
	}

	resp, err := h.client.SubscribeChannel(c.Request.Context(), &channelpb.SubscribeChannelRequest{
		ChannelId: channelID,
		UserId:    userID,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to subscribe to channel")
		return
	}

	c.JSON(http.StatusOK, gin.H{"success": resp.Success})
}

func (h *ChannelHandler) UnsubscribeChannel(c *gin.Context) {
	channelID := c.Param("id")
	userID := getUserID(c)
	if userID == "" {
		return
	}

	resp, err := h.client.UnsubscribeChannel(c.Request.Context(), &channelpb.UnsubscribeChannelRequest{
		ChannelId: channelID,
		UserId:    userID,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to unsubscribe from channel")
		return
	}

	c.JSON(http.StatusOK, gin.H{"success": resp.Success})
}

func (h *ChannelHandler) PublishChannelMessage(c *gin.Context) {
	channelID := c.Param("id")
	userID := getUserID(c)
	if userID == "" {
		return
	}

	var req struct {
		Content   string `json:"content"`
		Type      string `json:"type"`
		MediaUrl  string `json:"media_url"`
		MediaMime string `json:"media_mime"`
		MediaSize int64  `json:"media_size"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid json payload"})
		return
	}

	if req.Type == "" {
		req.Type = "text"
	}

	resp, err := h.client.PublishChannelMessage(c.Request.Context(), &channelpb.PublishChannelMessageRequest{
		ChannelId: channelID,
		SenderId:  userID,
		Content:   req.Content,
		Type:      req.Type,
		MediaUrl:  req.MediaUrl,
		MediaMime: req.MediaMime,
		MediaSize: req.MediaSize,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to publish channel message")
		return
	}

	c.JSON(http.StatusCreated, resp.Message)
}

func (h *ChannelHandler) GetChannelMessages(c *gin.Context) {
	channelID := c.Param("id")
	userID := getUserID(c)
	if userID == "" {
		return
	}

	limitStr := c.DefaultQuery("limit", "20")
	limit, _ := strconv.Atoi(limitStr)

	beforeStr := c.DefaultQuery("before", "0")
	before, _ := strconv.ParseInt(beforeStr, 10, 64)

	resp, err := h.client.GetChannelMessages(c.Request.Context(), &channelpb.GetChannelMessagesRequest{
		ChannelId:       channelID,
		RequesterId:     userID,
		Limit:           int32(limit),
		BeforeTimestamp: before,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to get channel messages")
		return
	}

	c.JSON(http.StatusOK, resp.Messages)
}

func (h *ChannelHandler) GetChannelMetadata(c *gin.Context) {
	channelID := c.Param("id")

	resp, err := h.client.GetChannelMetadata(c.Request.Context(), &channelpb.GetChannelMetadataRequest{
		ChannelId: channelID,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to get channel metadata")
		return
	}

	c.JSON(http.StatusOK, resp.Channel)
}

func (h *ChannelHandler) ListChannels(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}

	subscribedOnly := c.Query("subscribed")
	var filterUserID string
	if subscribedOnly == "true" {
		filterUserID = userID
	}

	resp, err := h.client.ListChannels(c.Request.Context(), &channelpb.ListChannelsRequest{
		UserId: filterUserID,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to list channels")
		return
	}

	c.JSON(http.StatusOK, resp.Channels)
}

func (h *ChannelHandler) handleGrpcError(c *gin.Context, err error, actionMsg string) {
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
