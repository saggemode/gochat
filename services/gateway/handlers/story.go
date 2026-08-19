package handlers

import (
	"net/http"

	"github.com/gin-gonic/gin"
	"go.uber.org/zap"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"

	storypb "gochat/gen/story"
)

type StoryHandler struct {
	client storypb.StoryServiceClient
	log    *zap.Logger
}

func NewStoryHandler(client storypb.StoryServiceClient, log *zap.Logger) *StoryHandler {
	return &StoryHandler{client: client, log: log}
}

func (h *StoryHandler) PostStory(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}

	var req struct {
		MediaUrl        string `json:"media_url"`
		MediaType       string `json:"media_type"`       // "text", "image", "video"
		Content         string `json:"content"`          // optional caption/text status content
		BackgroundColor string `json:"background_color"` // optional background hex code for text status
		FontStyle       string `json:"font_style"`       // optional font name for text status
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid json payload"})
		return
	}

	if req.MediaType == "" {
		req.MediaType = "text"
	}

	resp, err := h.client.PostStory(c.Request.Context(), &storypb.PostStoryRequest{
		UserId:          userID,
		MediaUrl:        req.MediaUrl,
		MediaType:       req.MediaType,
		Content:         req.Content,
		BackgroundColor: req.BackgroundColor,
		FontStyle:       req.FontStyle,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to post status story")
		return
	}

	c.JSON(http.StatusCreated, resp.Story)
}

func (h *StoryHandler) DeleteStory(c *gin.Context) {
	storyID := c.Param("id")
	userID := getUserID(c)
	if userID == "" {
		return
	}

	resp, err := h.client.DeleteStory(c.Request.Context(), &storypb.DeleteStoryRequest{
		StoryId: storyID,
		UserId:  userID,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to delete status story")
		return
	}

	c.JSON(http.StatusOK, gin.H{"success": resp.Success})
}

func (h *StoryHandler) GetStories(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}

	resp, err := h.client.GetStories(c.Request.Context(), &storypb.GetStoriesRequest{
		UserId: userID,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to retrieve active story feed")
		return
	}

	c.JSON(http.StatusOK, resp.Feed)
}

func (h *StoryHandler) ViewStory(c *gin.Context) {
	storyID := c.Param("id")
	userID := getUserID(c)
	if userID == "" {
		return
	}

	resp, err := h.client.ViewStory(c.Request.Context(), &storypb.ViewStoryRequest{
		StoryId:  storyID,
		ViewerId: userID,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to register story view")
		return
	}

	c.JSON(http.StatusOK, gin.H{"success": resp.Success})
}

func (h *StoryHandler) GetStoryViewerList(c *gin.Context) {
	storyID := c.Param("id")
	userID := getUserID(c)
	if userID == "" {
		return
	}

	resp, err := h.client.GetStoryViewerList(c.Request.Context(), &storypb.GetStoryViewerListRequest{
		StoryId: storyID,
		UserId:  userID,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to fetch story viewer list")
		return
	}

	c.JSON(http.StatusOK, resp.Viewers)
}

func (h *StoryHandler) handleGrpcError(c *gin.Context, err error, actionMsg string) {
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
