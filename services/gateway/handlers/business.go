package handlers

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"time"

	pb "gochat/gen/business"
	"gochat/pkg/fcm"
	"gochat/services/gateway/ws"

	"github.com/gin-gonic/gin"
	"github.com/redis/go-redis/v9"
	"go.uber.org/zap"
)

type BusinessHandler struct {
	client pb.BusinessServiceClient
	hub    *ws.Hub
	redis  *redis.Client
	log    *zap.Logger
}

func NewBusinessHandler(client pb.BusinessServiceClient, hub *ws.Hub, log *zap.Logger) *BusinessHandler {
	return &BusinessHandler{client: client, hub: hub, log: log}
}

func (h *BusinessHandler) WithRedis(r *redis.Client) *BusinessHandler {
	h.redis = r
	return h
}


func (h *BusinessHandler) CreateBusinessProfile(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	var req struct {
		BusinessName string `json:"business_name" binding:"required"`
		Category     string `json:"category"`
		Description  string `json:"description"`
		Address      string `json:"address"`
		Website      string `json:"website"`
		Email        string `json:"email"`
		Phone        string `json:"phone"`
		HoursJSON    string `json:"hours_json"`
		LogoURL      string `json:"logo_url"`
		BannerURL    string `json:"banner_url"`
		State        string  `json:"state"`
		CountryCode  string  `json:"country_code"`
		DeliveryFee  float64 `json:"delivery_fee"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	resp, err := h.client.CreateBusinessProfile(c.Request.Context(), &pb.CreateBusinessProfileRequest{
		UserId: userID, BusinessName: req.BusinessName, Category: req.Category,
		Description: req.Description, Address: req.Address, Website: req.Website,
		Email: req.Email, Phone: req.Phone, HoursJson: req.HoursJSON,
		LogoUrl: req.LogoURL, BannerUrl: req.BannerURL, State: req.State, CountryCode: req.CountryCode,
		DeliveryFee: req.DeliveryFee,
	})
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusCreated, resp)
}

func (h *BusinessHandler) UpdateBusinessProfile(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	var req struct {
		BusinessName string `json:"business_name"`
		Category     string `json:"category"`
		Description  string `json:"description"`
		Address      string `json:"address"`
		Website      string `json:"website"`
		Email        string `json:"email"`
		Phone        string `json:"phone"`
		HoursJSON    string `json:"hours_json"`
		LogoURL      string `json:"logo_url"`
		BannerURL    string `json:"banner_url"`
		State        string  `json:"state"`
		CountryCode  string  `json:"country_code"`
		DeliveryFee  float64 `json:"delivery_fee"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	resp, err := h.client.UpdateBusinessProfile(c.Request.Context(), &pb.UpdateBusinessProfileRequest{
		UserId: userID, BusinessName: req.BusinessName, Category: req.Category,
		Description: req.Description, Address: req.Address, Website: req.Website,
		Email: req.Email, Phone: req.Phone, HoursJson: req.HoursJSON,
		LogoUrl: req.LogoURL, BannerUrl: req.BannerURL, State: req.State, CountryCode: req.CountryCode,
		DeliveryFee: req.DeliveryFee,
	})
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	if resp != nil && resp.Profile != nil {
		go func() {
			pushCtx, pCancel := context.WithTimeout(context.Background(), 15*time.Second)
			defer pCancel()
			name := resp.Profile.BusinessName
			if name == "" {
				name = "A store you follow"
			}
			_ = fcm.NotifyStoreFollowersStoreEvent(pushCtx, userID, name,
				fmt.Sprintf("📢 %s updated their store!", name),
				fmt.Sprintf("Check out new store information, hours, and catalog from %s.", name),
				map[string]string{
					"event": "store_profile_updated",
				},
			)
		}()
	}

	c.JSON(http.StatusOK, resp)
}

func (h *BusinessHandler) GetBusinessProfile(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	resp, err := h.client.GetBusinessProfile(c.Request.Context(), &pb.GetBusinessProfileRequest{UserId: userID})
	if err != nil || resp == nil || resp.Profile == nil {
		c.JSON(http.StatusOK, gin.H{"profile": nil})
		return
	}
	p := resp.Profile

	followersCount := p.FollowersCount
	if followersCount == 0 {
		if storeResp, err := h.client.GetStore(c.Request.Context(), &pb.GetStoreRequest{UserId: userID}); err == nil && storeResp != nil && storeResp.Store != nil {
			if storeResp.Store.FollowersCount > 0 {
				followersCount = storeResp.Store.FollowersCount
			}
		}
	}

	c.JSON(http.StatusOK, gin.H{
		"profile": gin.H{
			"user_id":         p.UserId,
			"business_name":   p.BusinessName,
			"category":        p.Category,
			"description":     p.Description,
			"address":         p.Address,
			"website":         p.Website,
			"email":           p.Email,
			"phone":           p.Phone,
			"hours_json":      p.HoursJson,
			"is_verified":     p.IsVerified,
			"logo_url":        p.LogoUrl,
			"banner_url":      p.BannerUrl,
			"state":           p.State,
			"country_code":    p.CountryCode,
			"slug":            p.Slug,
			"followers_count": followersCount,
		},
	})
}

func (h *BusinessHandler) CreateCatalog(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	var req struct {
		Name string `json:"name" binding:"required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	resp, err := h.client.CreateCatalog(c.Request.Context(), &pb.CreateCatalogRequest{UserId: userID, Name: req.Name})
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusCreated, resp)
}

func (h *BusinessHandler) AddProduct(c *gin.Context) {
	catalogID := c.Param("id")
	var req struct {
		Name        string  `json:"name" binding:"required"`
		Description string  `json:"description"`
		Price       float64 `json:"price" binding:"required"`
		Currency    string  `json:"currency"`
		ImageURL    string  `json:"image_url"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	resp, err := h.client.AddProduct(c.Request.Context(), &pb.AddProductRequest{
		CatalogId: catalogID, Name: req.Name, Description: req.Description,
		Price: req.Price, Currency: req.Currency, ImageUrl: req.ImageURL,
	})
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusCreated, resp)
}

func (h *BusinessHandler) ListProducts(c *gin.Context) {
	catalogID := c.Param("id")
	resp, err := h.client.ListProducts(c.Request.Context(), &pb.ListProductsRequest{CatalogId: catalogID, Limit: 50})
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, resp)
}

func (h *BusinessHandler) CreateAppointmentSlot(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	var req struct {
		Title       string `json:"title" binding:"required"`
		Description string `json:"description"`
		StartTime   int64  `json:"start_time" binding:"required"`
		EndTime     int64  `json:"end_time" binding:"required"`
		MaxBookings int32  `json:"max_bookings"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	resp, err := h.client.CreateAppointmentSlot(c.Request.Context(), &pb.CreateAppointmentSlotRequest{
		BusinessId: userID, Title: req.Title, Description: req.Description,
		StartTime: req.StartTime, EndTime: req.EndTime, MaxBookings: req.MaxBookings,
	})
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusCreated, resp)
}

func (h *BusinessHandler) BookAppointment(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	apptID := c.Param("id")
	var req struct {
		Notes string `json:"notes"`
	}
	c.ShouldBindJSON(&req)
	resp, err := h.client.BookAppointment(c.Request.Context(), &pb.BookAppointmentRequest{
		UserId: userID, AppointmentId: apptID, Notes: req.Notes,
	})
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, resp)
}

func (h *BusinessHandler) ListAppointments(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	resp, err := h.client.ListAppointments(c.Request.Context(), &pb.ListAppointmentsRequest{BusinessId: userID, Limit: 50})
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, resp)
}

func (h *BusinessHandler) SetAutoReply(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	var req struct {
		TriggerType  string  `json:"trigger_type" binding:"required"`
		TriggerValue string  `json:"trigger_value"`
		ReplyText    string  `json:"reply_text" binding:"required"`
		ScheduleType string  `json:"schedule_type"`
		Timezone     string  `json:"timezone"`
		DaysOfWeek   []int32 `json:"days_of_week"`
		StartTime    string  `json:"start_time"`
		EndTime      string  `json:"end_time"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	resp, err := h.client.SetAutoReply(c.Request.Context(), &pb.SetAutoReplyRequest{
		UserId: userID, TriggerType: req.TriggerType, TriggerValue: req.TriggerValue, ReplyText: req.ReplyText,
	})
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusCreated, resp)
}

func (h *BusinessHandler) GetAutoReplies(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	resp, err := h.client.GetAutoReplies(c.Request.Context(), &pb.GetAutoRepliesRequest{UserId: userID})
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, resp)
}

func (h *BusinessHandler) EnqueueCustomer(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	var req struct {
		CustomerID string `json:"customer_id" binding:"required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	resp, err := h.client.EnqueueCustomer(c.Request.Context(), &pb.EnqueueCustomerRequest{BusinessId: userID, CustomerId: req.CustomerID})
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusCreated, resp)
}

func (h *BusinessHandler) DequeueCustomer(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	resp, err := h.client.DequeueCustomer(c.Request.Context(), &pb.DequeueCustomerRequest{BusinessId: userID})
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, resp)
}

func (h *BusinessHandler) GetQueuePosition(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	var req struct {
		BusinessID string `json:"business_id" binding:"required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	resp, err := h.client.GetQueuePosition(c.Request.Context(), &pb.GetQueuePositionRequest{BusinessId: req.BusinessID, CustomerId: userID})
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, resp)
}

func (h *BusinessHandler) ToggleFollowStore(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	storeID := c.Param("id")
	resp, err := h.client.ToggleFollowStore(c.Request.Context(), &pb.ToggleFollowStoreRequest{
		StoreId: storeID, UserId: userID,
	})
	if err != nil {
		if strings.Contains(err.Error(), "store not found") {
			c.JSON(http.StatusNotFound, gin.H{"error": "Store not found"})
			return
		}
		if strings.Contains(err.Error(), "cannot follow your own store") {
			c.JSON(http.StatusBadRequest, gin.H{"error": "You cannot follow your own store"})
			return
		}
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, resp)
}

func (h *BusinessHandler) GetFollowedStores(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	resp, err := h.client.GetFollowedStores(c.Request.Context(), &pb.GetFollowedStoresRequest{
		UserId: userID,
	})
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, resp)
}

func (h *BusinessHandler) IsFollowingStore(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	storeID := c.Param("id")
	resp, err := h.client.IsFollowingStore(c.Request.Context(), &pb.IsFollowingStoreRequest{
		StoreId: storeID, UserId: userID,
	})
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, resp)
}

// ── Quick Replies ────────────────────────────────────────────────────────────

type QuickReply struct {
	ID        string `json:"id"`
	Shortcut  string `json:"shortcut"`
	ReplyText string `json:"reply_text"`
}

func (h *BusinessHandler) ListQuickReplies(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}

	key := fmt.Sprintf("biz:quick_replies:%s", userID)
	var replies []QuickReply
	if h.redis != nil {
		if val, err := h.redis.Get(c.Request.Context(), key).Result(); err == nil && val != "" {
			_ = json.Unmarshal([]byte(val), &replies)
		}
	}

	if len(replies) == 0 {
		// Provide high-utility business templates if none customized yet
		replies = []QuickReply{
			{ID: "qr_1", Shortcut: "/hours", ReplyText: "Our business hours are Monday – Saturday, 9:00 AM – 6:00 PM."},
			{ID: "qr_2", Shortcut: "/location", ReplyText: "We are located at our main store. Check our profile for complete directions!"},
			{ID: "qr_3", Shortcut: "/catalog", ReplyText: "You can browse our entire product catalog directly here in our store profile."},
			{ID: "qr_4", Shortcut: "/shipping", ReplyText: "We ship orders within 24 hours. Domestic delivery takes 1–3 business days with tracking."},
			{ID: "qr_5", Shortcut: "/thanks", ReplyText: "Thank you for reaching out! We appreciate your business and will assist you shortly."},
		}
	}

	c.JSON(http.StatusOK, gin.H{"quick_replies": replies})
}

func (h *BusinessHandler) CreateQuickReply(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}

	var req struct {
		Shortcut  string `json:"shortcut" binding:"required"`
		ReplyText string `json:"reply_text" binding:"required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	shortcut := req.Shortcut
	if !strings.HasPrefix(shortcut, "/") {
		shortcut = "/" + shortcut
	}

	key := fmt.Sprintf("biz:quick_replies:%s", userID)
	var replies []QuickReply
	if h.redis != nil {
		if val, err := h.redis.Get(c.Request.Context(), key).Result(); err == nil && val != "" {
			_ = json.Unmarshal([]byte(val), &replies)
		}
	}

	newReply := QuickReply{
		ID:        fmt.Sprintf("qr_%d", time.Now().UnixNano()),
		Shortcut:  shortcut,
		ReplyText: req.ReplyText,
	}

	replaced := false
	for i, r := range replies {
		if strings.EqualFold(r.Shortcut, shortcut) {
			replies[i] = newReply
			replaced = true
			break
		}
	}
	if !replaced {
		replies = append(replies, newReply)
	}

	if h.redis != nil {
		if bytes, err := json.Marshal(replies); err == nil {
			_ = h.redis.Set(c.Request.Context(), key, string(bytes), 365*24*time.Hour).Err()
		}
	}

	c.JSON(http.StatusCreated, newReply)
}

func (h *BusinessHandler) DeleteQuickReply(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	qrID := c.Param("id")

	key := fmt.Sprintf("biz:quick_replies:%s", userID)
	var replies []QuickReply
	if h.redis != nil {
		if val, err := h.redis.Get(c.Request.Context(), key).Result(); err == nil && val != "" {
			_ = json.Unmarshal([]byte(val), &replies)
		}
	}

	filtered := make([]QuickReply, 0, len(replies))
	for _, r := range replies {
		if r.ID != qrID && !strings.EqualFold(r.Shortcut, qrID) {
			filtered = append(filtered, r)
		}
	}

	if h.redis != nil {
		if bytes, err := json.Marshal(filtered); err == nil {
			_ = h.redis.Set(c.Request.Context(), key, string(bytes), 365*24*time.Hour).Err()
		}
	}

	c.JSON(http.StatusOK, gin.H{"success": true})
}

