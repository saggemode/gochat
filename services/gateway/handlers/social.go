package handlers

import (
	"net/http"

	pb "gochat/gen/social"

	"github.com/gin-gonic/gin"
	"go.uber.org/zap"
)

type SocialHandler struct {
	client pb.SocialServiceClient
	log    *zap.Logger
}

func NewSocialHandler(client pb.SocialServiceClient, log *zap.Logger) *SocialHandler {
	return &SocialHandler{client: client, log: log}
}

func (h *SocialHandler) FollowUser(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	targetID := c.Param("id")
	resp, err := h.client.FollowUser(c.Request.Context(), &pb.FollowUserRequest{UserId: userID, TargetId: targetID})
	if err != nil { c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()}); return }
	c.JSON(http.StatusOK, resp)
}

func (h *SocialHandler) UnfollowUser(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	targetID := c.Param("id")
	resp, err := h.client.UnfollowUser(c.Request.Context(), &pb.UnfollowUserRequest{UserId: userID, TargetId: targetID})
	if err != nil { c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()}); return }
	c.JSON(http.StatusOK, resp)
}

func (h *SocialHandler) GetFollowers(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	resp, err := h.client.GetFollowers(c.Request.Context(), &pb.GetFollowersRequest{UserId: userID, Limit: 50})
	if err != nil { c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()}); return }
	c.JSON(http.StatusOK, resp)
}

func (h *SocialHandler) GetFollowing(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	resp, err := h.client.GetFollowing(c.Request.Context(), &pb.GetFollowingRequest{UserId: userID, Limit: 50})
	if err != nil { c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()}); return }
	c.JSON(http.StatusOK, resp)
}

func (h *SocialHandler) CreateMoment(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	var req struct {
		Content    string `json:"content"`
		MediaURL   string `json:"media_url"`
		MediaType  string `json:"media_type"`
		Visibility string `json:"visibility"`
	}
	c.ShouldBindJSON(&req)
	resp, err := h.client.CreateMoment(c.Request.Context(), &pb.CreateMomentRequest{
		UserId: userID, Content: req.Content, MediaUrl: req.MediaURL,
		MediaType: req.MediaType, Visibility: req.Visibility,
	})
	if err != nil { c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()}); return }
	c.JSON(http.StatusCreated, resp)
}

func (h *SocialHandler) LikeMoment(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	momentID := c.Param("id")
	resp, err := h.client.LikeMoment(c.Request.Context(), &pb.LikeMomentRequest{UserId: userID, MomentId: momentID})
	if err != nil { c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()}); return }
	c.JSON(http.StatusOK, resp)
}

func (h *SocialHandler) CommentMoment(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	momentID := c.Param("id")
	var req struct{ Content string `json:"content" binding:"required"` }
	if err := c.ShouldBindJSON(&req); err != nil { c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()}); return }
	resp, err := h.client.CommentMoment(c.Request.Context(), &pb.CommentMomentRequest{
		UserId: userID, MomentId: momentID, Content: req.Content,
	})
	if err != nil { c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()}); return }
	c.JSON(http.StatusCreated, resp)
}

func (h *SocialHandler) GetMomentsFeed(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	resp, err := h.client.GetMomentsFeed(c.Request.Context(), &pb.GetMomentsFeedRequest{UserId: userID, Limit: 20})
	if err != nil { c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()}); return }
	c.JSON(http.StatusOK, resp)
}

func (h *SocialHandler) SetNearbyVisible(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	var req struct {
		Latitude  float64 `json:"latitude"`
		Longitude float64 `json:"longitude"`
		IsVisible bool    `json:"is_visible"`
		RadiusKM  int32   `json:"radius_km"`
	}
	c.ShouldBindJSON(&req)
	resp, err := h.client.SetNearbyVisible(c.Request.Context(), &pb.SetNearbyVisibleRequest{
		UserId: userID, Latitude: req.Latitude, Longitude: req.Longitude,
		IsVisible: req.IsVisible, RadiusKm: req.RadiusKM,
	})
	if err != nil { c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()}); return }
	c.JSON(http.StatusOK, resp)
}

func (h *SocialHandler) GetNearbyUsers(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	resp, err := h.client.GetNearbyUsers(c.Request.Context(), &pb.GetNearbyUsersRequest{UserId: userID, RadiusKm: 5, Limit: 20})
	if err != nil { c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()}); return }
	c.JSON(http.StatusOK, resp)
}

func (h *SocialHandler) ApplyForBadge(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	var req struct{ BadgeType string `json:"badge_type" binding:"required"` }
	if err := c.ShouldBindJSON(&req); err != nil { c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()}); return }
	resp, err := h.client.ApplyForBadge(c.Request.Context(), &pb.ApplyForBadgeRequest{UserId: userID, BadgeType: req.BadgeType})
	if err != nil { c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()}); return }
	c.JSON(http.StatusOK, resp)
}

func (h *SocialHandler) GetUserBadges(c *gin.Context) {
	targetID := c.Param("id")
	resp, err := h.client.GetUserBadges(c.Request.Context(), &pb.GetUserBadgesRequest{UserId: targetID})
	if err != nil { c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()}); return }
	c.JSON(http.StatusOK, resp)
}

func (h *SocialHandler) CreateAudioRoom(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	var req struct {
		Title       string `json:"title" binding:"required"`
		MaxSpeakers int32  `json:"max_speakers"`
	}
	if err := c.ShouldBindJSON(&req); err != nil { c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()}); return }
	resp, err := h.client.CreateAudioRoom(c.Request.Context(), &pb.CreateAudioRoomRequest{
		UserId: userID, Title: req.Title, MaxSpeakers: req.MaxSpeakers,
	})
	if err != nil { c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()}); return }
	c.JSON(http.StatusCreated, resp)
}

func (h *SocialHandler) JoinAudioRoom(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	roomID := c.Param("id")
	resp, err := h.client.JoinAudioRoom(c.Request.Context(), &pb.JoinAudioRoomRequest{UserId: userID, RoomId: roomID, Role: "listener"})
	if err != nil { c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()}); return }
	c.JSON(http.StatusOK, resp)
}

func (h *SocialHandler) LeaveAudioRoom(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	roomID := c.Param("id")
	resp, err := h.client.LeaveAudioRoom(c.Request.Context(), &pb.LeaveAudioRoomRequest{UserId: userID, RoomId: roomID})
	if err != nil { c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()}); return }
	c.JSON(http.StatusOK, resp)
}

func (h *SocialHandler) ListAudioRooms(c *gin.Context) {
	resp, err := h.client.ListAudioRooms(c.Request.Context(), &pb.ListAudioRoomsRequest{Limit: 20})
	if err != nil { c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()}); return }
	c.JSON(http.StatusOK, resp)
}
