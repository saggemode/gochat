package handlers

import (
	"net/http"
	"strings"
	"time"

	authpb "gochat/gen/auth"
	pb "gochat/gen/social"

	"github.com/gin-gonic/gin"
	"go.uber.org/zap"
)

type SocialHandler struct {
	client     pb.SocialServiceClient
	authClient authpb.AuthServiceClient
	log        *zap.Logger
}

func NewSocialHandler(client pb.SocialServiceClient, authClient authpb.AuthServiceClient, log *zap.Logger) *SocialHandler {
	return &SocialHandler{client: client, authClient: authClient, log: log}
}

func (h *SocialHandler) SearchUsers(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}

	query := strings.TrimSpace(c.Query("q"))
	if query == "" {
		c.JSON(http.StatusOK, []any{})
		return
	}

	// First attempt direct identifier/PIN lookup via GetUsers
	resp, err := h.authClient.GetUsers(c.Request.Context(), &authpb.GetUsersRequest{UserIds: []string{query}})
	if err != nil || len(resp.Users) == 0 {
		// Fallback to fetching all users for fuzzy search
		resp, err = h.authClient.GetUsers(c.Request.Context(), &authpb.GetUsersRequest{UserIds: []string{}})
		if err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}
	}

	type searchUser struct {
		ID          string `json:"id"`
		Name        string `json:"name"`
		Email       string `json:"email"`
		Phone       string `json:"phone"`
		Pin         string `json:"pin"`
		Avatar      string `json:"avatar"`
		IsFollowing bool   `json:"isFollowing"`
		IsSelf      bool   `json:"isSelf"`
	}

	followingResp, err := h.client.GetFollowing(c.Request.Context(), &pb.GetFollowingRequest{UserId: userID, Limit: 200})
	followingSet := make(map[string]struct{}, len(followingResp.FollowingIds))
	if err == nil {
		for _, id := range followingResp.FollowingIds {
			followingSet[id] = struct{}{}
		}
	}

	matches := make([]searchUser, 0)
	for _, u := range resp.Users {
		if u.Id == userID {
			continue
		}
		name := strings.ToLower(strings.TrimSpace(u.DisplayName))
		email := strings.ToLower(strings.TrimSpace(u.Email))
		phone := strings.ToLower(strings.TrimSpace(u.Phone))
		pin := strings.ToLower(strings.TrimSpace(u.Pin))
		queryLower := strings.ToLower(query)
		if name == "" && email == "" && phone == "" && pin == "" {
			continue
		}
		if !strings.Contains(name, queryLower) && !strings.Contains(email, queryLower) && !strings.Contains(phone, queryLower) && !strings.Contains(pin, queryLower) {
			continue
		}
		_, isFollowing := followingSet[u.Id]
		matches = append(matches, searchUser{
			ID:          u.Id,
			Name:        u.DisplayName,
			Email:       u.Email,
			Phone:       u.Phone,
			Pin:         u.Pin,
			Avatar:      u.AvatarUrl,
			IsFollowing: isFollowing,
			IsSelf:      u.Id == userID,
		})
	}

	c.JSON(http.StatusOK, matches)
}

func (h *SocialHandler) FollowUser(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	targetID := c.Param("id")
	resp, err := h.client.FollowUser(c.Request.Context(), &pb.FollowUserRequest{UserId: userID, TargetId: targetID})
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, resp)
}

func (h *SocialHandler) UnfollowUser(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	targetID := c.Param("id")
	resp, err := h.client.UnfollowUser(c.Request.Context(), &pb.UnfollowUserRequest{UserId: userID, TargetId: targetID})
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, resp)
}

func (h *SocialHandler) GetFollowers(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	resp, err := h.client.GetFollowers(c.Request.Context(), &pb.GetFollowersRequest{UserId: userID, Limit: 50})
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, resp)
}

func (h *SocialHandler) GetFollowing(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	resp, err := h.client.GetFollowing(c.Request.Context(), &pb.GetFollowingRequest{UserId: userID, Limit: 50})
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
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
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusCreated, resp)
}

type EnrichedComment struct {
	ID              string `json:"id"`
	UserID          string `json:"user_id"`
	UserDisplayName string `json:"user_display_name"`
	Text            string `json:"text"`
	CreatedAt       int64  `json:"created_at"`
}

type EnrichedMoment struct {
	ID              string             `json:"id"`
	UserID          string             `json:"user_id"`
	Caption         string             `json:"caption"`
	Content         string             `json:"content"`
	MediaURL        string             `json:"media_url"`
	MediaType       string             `json:"media_type"`
	Visibility      string             `json:"visibility"`
	LikesCount      int32              `json:"likes_count"`
	CommentCount    int32              `json:"comment_count"`
	CreatedAt       string             `json:"created_at"`
	HasLiked        bool               `json:"has_liked"`
	UserDisplayName string             `json:"user_display_name"`
	UserAvatarURL   string             `json:"user_avatar_url"`
	Comments        []*EnrichedComment `json:"comments"`
}

func (h *SocialHandler) GetMomentsFeed(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	resp, err := h.client.GetMomentsFeed(c.Request.Context(), &pb.GetMomentsFeedRequest{UserId: userID, Limit: 20})
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	// Collect unique user IDs to resolve profile information
	userIDsMap := make(map[string]bool)
	for _, m := range resp.Moments {
		if m.UserId != "" {
			userIDsMap[m.UserId] = true
		}
		for _, comment := range m.Comments {
			if comment.UserId != "" {
				userIDsMap[comment.UserId] = true
			}
		}
	}

	uniqueUserIDs := make([]string, 0, len(userIDsMap))
	for id := range userIDsMap {
		uniqueUserIDs = append(uniqueUserIDs, id)
	}

	// Fetch users display names & avatar URLs from the Auth service
	namesMap := make(map[string]string)
	avatarsMap := make(map[string]string)
	if len(uniqueUserIDs) > 0 {
		authResp, err := h.authClient.GetUsers(c.Request.Context(), &authpb.GetUsersRequest{UserIds: uniqueUserIDs})
		if err == nil {
			for _, u := range authResp.Users {
				namesMap[u.Id] = u.DisplayName
				avatarsMap[u.Id] = u.AvatarUrl
			}
		} else {
			h.log.Error("failed to fetch user profiles for feed enrichment", zap.Error(err))
		}
	}

	enrichedMoments := make([]*EnrichedMoment, len(resp.Moments))
	for i, m := range resp.Moments {
		hasLiked := false
		visibility := m.Visibility
		if strings.HasSuffix(visibility, "_LIKED") {
			hasLiked = true
			visibility = strings.TrimSuffix(visibility, "_LIKED")
		}

		authorName := namesMap[m.UserId]
		if authorName == "" {
			authorName = "Gochat User"
		}
		authorAvatar := avatarsMap[m.UserId]

		comments := make([]*EnrichedComment, len(m.Comments))
		for j, comment := range m.Comments {
			commenterName := namesMap[comment.UserId]
			if commenterName == "" {
				commenterName = "Gochat User"
			}
			comments[j] = &EnrichedComment{
				ID:              comment.Id,
				UserID:          comment.UserId,
				UserDisplayName: commenterName,
				Text:            comment.Content,
				CreatedAt:       comment.CreatedAt,
			}
		}

		enrichedMoments[i] = &EnrichedMoment{
			ID:              m.Id,
			UserID:          m.UserId,
			Caption:         m.Content,
			Content:         m.Content,
			MediaURL:        m.MediaUrl,
			MediaType:       m.MediaType,
			Visibility:      visibility,
			LikesCount:      m.LikeCount,
			CommentCount:    m.CommentCount,
			CreatedAt:       time.Unix(m.CreatedAt, 0).Format(time.RFC3339),
			HasLiked:        hasLiked,
			UserDisplayName: authorName,
			UserAvatarURL:   authorAvatar,
			Comments:        comments,
		}
	}

	c.JSON(http.StatusOK, gin.H{
		"moments": enrichedMoments,
		"total":   resp.Total,
	})
}

func (h *SocialHandler) LikeMoment(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	momentID := c.Param("id")
	resp, err := h.client.LikeMoment(c.Request.Context(), &pb.LikeMomentRequest{UserId: userID, MomentId: momentID})
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, resp)
}

func (h *SocialHandler) CommentMoment(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	momentID := c.Param("id")
	var req struct {
		Content string `json:"content" binding:"required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	resp, err := h.client.CommentMoment(c.Request.Context(), &pb.CommentMomentRequest{
		UserId: userID, MomentId: momentID, Content: req.Content,
	})
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusCreated, resp)
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
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, resp)
}

func (h *SocialHandler) GetNearbyUsers(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	resp, err := h.client.GetNearbyUsers(c.Request.Context(), &pb.GetNearbyUsersRequest{UserId: userID, RadiusKm: 5, Limit: 20})
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, resp)
}

func (h *SocialHandler) ApplyForBadge(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	var req struct {
		BadgeType string `json:"badge_type" binding:"required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	resp, err := h.client.ApplyForBadge(c.Request.Context(), &pb.ApplyForBadgeRequest{UserId: userID, BadgeType: req.BadgeType})
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, resp)
}

func (h *SocialHandler) GetUserBadges(c *gin.Context) {
	targetID := c.Param("id")
	resp, err := h.client.GetUserBadges(c.Request.Context(), &pb.GetUserBadgesRequest{UserId: targetID})
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
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
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	resp, err := h.client.CreateAudioRoom(c.Request.Context(), &pb.CreateAudioRoomRequest{
		UserId: userID, Title: req.Title, MaxSpeakers: req.MaxSpeakers,
	})
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusCreated, resp)
}

func (h *SocialHandler) JoinAudioRoom(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	roomID := c.Param("id")
	resp, err := h.client.JoinAudioRoom(c.Request.Context(), &pb.JoinAudioRoomRequest{UserId: userID, RoomId: roomID, Role: "listener"})
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, resp)
}

func (h *SocialHandler) LeaveAudioRoom(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	roomID := c.Param("id")
	resp, err := h.client.LeaveAudioRoom(c.Request.Context(), &pb.LeaveAudioRoomRequest{UserId: userID, RoomId: roomID})
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, resp)
}

func (h *SocialHandler) ListAudioRooms(c *gin.Context) {
	resp, err := h.client.ListAudioRooms(c.Request.Context(), &pb.ListAudioRoomsRequest{Limit: 20})
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, resp)
}
