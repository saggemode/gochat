package handlers

import (
	"net/http"

	pb "gochat/gen/business"

	"github.com/gin-gonic/gin"
	"go.uber.org/zap"
)

type BusinessHandler struct {
	client pb.BusinessServiceClient
	log    *zap.Logger
}

func NewBusinessHandler(client pb.BusinessServiceClient, log *zap.Logger) *BusinessHandler {
	return &BusinessHandler{client: client, log: log}
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
		State        string `json:"state"`
		CountryCode  string `json:"country_code"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	resp, err := h.client.CreateBusinessProfile(c.Request.Context(), &pb.CreateBusinessProfileRequest{
		UserId: userID, BusinessName: req.BusinessName, Category: req.Category,
		Description: req.Description, Address: req.Address, Website: req.Website,
		Email: req.Email, Phone: req.Phone, HoursJson: req.HoursJSON,
		// LogoUrl: req.LogoURL, BannerUrl: req.BannerURL, State: req.State, CountryCode: req.CountryCode,
	})
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusCreated, resp)
}

func (h *BusinessHandler) GetBusinessProfile(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	resp, err := h.client.GetBusinessProfile(c.Request.Context(), &pb.GetBusinessProfileRequest{UserId: userID})
	if err != nil {
		c.JSON(http.StatusOK, gin.H{"profile": nil})
		return
	}
	c.JSON(http.StatusOK, resp)
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
