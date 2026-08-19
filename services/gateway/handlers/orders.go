package handlers

import (
	"net/http"
	"strconv"
	"strings"

	pb "gochat/gen/business"

	"github.com/gin-gonic/gin"
	"google.golang.org/grpc"
)

var jsonOpt = grpc.CallContentSubtype("json_proto")

// ── Cart Handlers ────────────────────────────────────────────────────────────

func (h *BusinessHandler) AddToCart(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}

	var req struct {
		ProductID string `json:"product_id" binding:"required"`
		Quantity  int32  `json:"quantity"`
		VariantID string `json:"variant_id"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	if req.Quantity <= 0 {
		req.Quantity = 1
	}

	resp, err := h.client.AddToCart(c.Request.Context(), &pb.AddToCartRequest{
		UserId:    userID,
		ProductId: req.ProductID,
		Quantity:  req.Quantity,
		VariantId: req.VariantID,
	}, jsonOpt)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, resp.Cart)
}

func (h *BusinessHandler) GetCart(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}

	resp, err := h.client.GetCart(c.Request.Context(), &pb.GetCartRequest{UserId: userID}, jsonOpt)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, resp.Cart)
}

func (h *BusinessHandler) UpdateCartItem(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}

	cartItemID := c.Param("id")

	var req struct {
		Quantity int32 `json:"quantity"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	resp, err := h.client.UpdateCartItem(c.Request.Context(), &pb.UpdateCartItemRequest{
		UserId:     userID,
		CartItemId: cartItemID,
		Quantity:   req.Quantity,
	}, jsonOpt)
	if err != nil {
		errMsg := err.Error()
		// Return stock limit errors as 400 Bad Request
		if strings.Contains(errMsg, "cannot exceed available stock") || strings.Contains(errMsg, "insufficient") {
			c.JSON(http.StatusBadRequest, gin.H{"error": errMsg})
			return
		}
		c.JSON(http.StatusInternalServerError, gin.H{"error": errMsg})
		return
	}

	c.JSON(http.StatusOK, resp.Cart)
}

func (h *BusinessHandler) RemoveFromCart(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}

	cartItemID := c.Param("id")

	resp, err := h.client.RemoveFromCart(c.Request.Context(), &pb.RemoveFromCartRequest{
		UserId:     userID,
		CartItemId: cartItemID,
	}, jsonOpt)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, resp.Cart)
}

func (h *BusinessHandler) ClearCart(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}

	resp, err := h.client.ClearCart(c.Request.Context(), &pb.ClearCartRequest{UserId: userID}, jsonOpt)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{"success": resp.Success})
}

// ── Orders Handlers ──────────────────────────────────────────────────────────

func (h *BusinessHandler) CreateOrders(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}

	var req struct {
		ShippingName    string `json:"shipping_name" binding:"required"`
		ShippingPhone   string `json:"shipping_phone" binding:"required"`
		ShippingAddress string `json:"shipping_address" binding:"required"`
		ShippingCity    string `json:"shipping_city"`
		ShippingState   string `json:"shipping_state"`
		ShippingCountry string `json:"shipping_country"`
		PaymentMethod   string `json:"payment_method"`
		Notes           string `json:"notes"`
		Orders          []struct {
			BusinessID  string  `json:"business_id" binding:"required"`
			CouponCode  string  `json:"coupon_code"`
			ShippingFee float64 `json:"shipping_fee"`
			FxQuote     *struct {
				TargetCurrency      string             `json:"target_currency"`
				SourceCurrencies    []string           `json:"source_currencies"`
				BuyerTotalAmount    float64            `json:"buyer_total_amount"`
				BuyerShippingFee    float64            `json:"buyer_shipping_fee"`
				BuyerDiscountAmount float64            `json:"buyer_discount_amount"`
				BuyerGrandTotal     float64            `json:"buyer_grand_total"`
				Rates               map[string]float64 `json:"rates"`
				Provider            string             `json:"provider"`
				AsOf                string             `json:"as_of"`
				Stale               bool               `json:"stale"`
				LockedAt            string             `json:"locked_at"`
			} `json:"fx_quote"`
			Items []struct {
				ProductID string `json:"product_id" binding:"required"`
				Quantity  int32  `json:"quantity" binding:"required"`
			} `json:"items" binding:"required"`
		} `json:"orders" binding:"required"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	pbOrdersInput := make([]*pb.CreateSingleOrderInput, len(req.Orders))
	for i, oReq := range req.Orders {
		pbItemsInput := make([]*pb.CreateOrderItemInput, len(oReq.Items))
		for j, itemReq := range oReq.Items {
			pbItemsInput[j] = &pb.CreateOrderItemInput{
				ProductId: itemReq.ProductID,
				Quantity:  itemReq.Quantity,
			}
		}
		var pbFxQuote *pb.OrderFxQuote
		if oReq.FxQuote != nil {
			pbFxQuote = &pb.OrderFxQuote{
				TargetCurrency:      oReq.FxQuote.TargetCurrency,
				SourceCurrencies:    oReq.FxQuote.SourceCurrencies,
				BuyerTotalAmount:    oReq.FxQuote.BuyerTotalAmount,
				BuyerShippingFee:    oReq.FxQuote.BuyerShippingFee,
				BuyerDiscountAmount: oReq.FxQuote.BuyerDiscountAmount,
				BuyerGrandTotal:     oReq.FxQuote.BuyerGrandTotal,
				Rates:               oReq.FxQuote.Rates,
				Provider:            oReq.FxQuote.Provider,
				AsOf:                oReq.FxQuote.AsOf,
				Stale:               oReq.FxQuote.Stale,
				LockedAt:            oReq.FxQuote.LockedAt,
			}
		}

		pbOrdersInput[i] = &pb.CreateSingleOrderInput{
			BusinessId:  oReq.BusinessID,
			CouponCode:  oReq.CouponCode,
			ShippingFee: oReq.ShippingFee,
			Items:       pbItemsInput,
			FxQuote:     pbFxQuote,
		}
	}

	resp, err := h.client.CreateOrders(c.Request.Context(), &pb.CreateOrdersRequest{
		BuyerId:         userID,
		ShippingName:    req.ShippingName,
		ShippingPhone:   req.ShippingPhone,
		ShippingAddress: req.ShippingAddress,
		ShippingCity:    req.ShippingCity,
		ShippingState:   req.ShippingState,
		ShippingCountry: req.ShippingCountry,
		PaymentMethod:   req.PaymentMethod,
		Notes:           req.Notes,
		Orders:          pbOrdersInput,
	}, jsonOpt)

	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, resp.Orders)
}

func (h *BusinessHandler) GetOrder(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}

	orderID := c.Param("id")

	resp, err := h.client.GetOrder(c.Request.Context(), &pb.GetOrderRequest{
		Id:     orderID,
		UserId: userID,
	}, jsonOpt)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, resp.Order)
}

func (h *BusinessHandler) ListBuyerOrders(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}

	limit, _ := strconv.Atoi(c.DefaultQuery("limit", "20"))
	offset, _ := strconv.Atoi(c.DefaultQuery("offset", "0"))

	resp, err := h.client.ListBuyerOrders(c.Request.Context(), &pb.ListBuyerOrdersRequest{
		BuyerId: userID,
		Limit:   int32(limit),
		Offset:  int32(offset),
	}, jsonOpt)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"orders": resp.Orders,
		"total":  resp.Total,
	})
}

func (h *BusinessHandler) ListSellerOrders(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}

	statusFilter := c.Query("status")
	limit, _ := strconv.Atoi(c.DefaultQuery("limit", "20"))
	offset, _ := strconv.Atoi(c.DefaultQuery("offset", "0"))

	resp, err := h.client.ListSellerOrders(c.Request.Context(), &pb.ListSellerOrdersRequest{
		BusinessId: userID,
		Status:     statusFilter,
		Limit:      int32(limit),
		Offset:     int32(offset),
	}, jsonOpt)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"orders": resp.Orders,
		"total":  resp.Total,
	})
}

func (h *BusinessHandler) UpdateOrderStatus(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}

	orderID := c.Param("id")

	var req struct {
		Status string `json:"status" binding:"required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	resp, err := h.client.UpdateOrderStatus(c.Request.Context(), &pb.UpdateOrderStatusRequest{
		Id:         orderID,
		BusinessId: userID,
		Status:     req.Status,
	}, jsonOpt)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, resp.Order)
}

func (h *BusinessHandler) UpdateOrderTracking(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}

	orderID := c.Param("id")

	var req struct {
		TrackingNumber        string `json:"tracking_number"`
		TrackingCarrier       string `json:"tracking_carrier"`
		TrackingURL           string `json:"tracking_url"`
		EstimatedDeliveryDate string `json:"estimated_delivery_date"`
		DeliveryNotes         string `json:"delivery_notes"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	resp, err := h.client.UpdateOrderTracking(c.Request.Context(), &pb.UpdateOrderTrackingRequest{
		Id:                    orderID,
		BusinessId:            userID,
		TrackingNumber:        req.TrackingNumber,
		TrackingCarrier:       req.TrackingCarrier,
		TrackingUrl:           req.TrackingURL,
		EstimatedDeliveryDate: req.EstimatedDeliveryDate,
		DeliveryNotes:         req.DeliveryNotes,
	}, jsonOpt)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, resp.Order)
}

// ── Coupons Handlers ────────────────────────────────────────────────────────

func (h *BusinessHandler) CreateCoupon(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}

	var req struct {
		Code           string  `json:"code" binding:"required"`
		DiscountType   string  `json:"discount_type"`
		DiscountValue  float64 `json:"discount_value" binding:"required"`
		MinSpend       float64 `json:"min_spend"`
		MaxUses        int32   `json:"max_uses"`
		ExpiresAt      string  `json:"expires_at"`
		ProductID      string  `json:"product_id"`
		MaxUsesPerUser int32   `json:"max_uses_per_user"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	resp, err := h.client.CreateCoupon(c.Request.Context(), &pb.CreateCouponRequest{
		BusinessId:     userID,
		Code:           req.Code,
		DiscountType:   req.DiscountType,
		DiscountValue:  req.DiscountValue,
		MinSpend:       req.MinSpend,
		MaxUses:        req.MaxUses,
		ExpiresAt:      req.ExpiresAt,
		ProductId:      req.ProductID,
		MaxUsesPerUser: req.MaxUsesPerUser,
	}, jsonOpt)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, resp.Coupon)
}

func (h *BusinessHandler) ListBusinessCoupons(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}

	resp, err := h.client.ListBusinessCoupons(c.Request.Context(), &pb.ListBusinessCouponsRequest{BusinessId: userID}, jsonOpt)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, resp.Coupons)
}

func (h *BusinessHandler) ValidateCoupon(c *gin.Context) {
	userID := getUserID(c)

	var req struct {
		BusinessID string   `json:"business_id" binding:"required"`
		Code       string   `json:"code" binding:"required"`
		Subtotal   float64  `json:"subtotal"`
		ProductIDs []string `json:"product_ids"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	resp, err := h.client.ValidateCoupon(c.Request.Context(), &pb.ValidateCouponRequest{
		BusinessId: req.BusinessID,
		Code:       req.Code,
		Subtotal:   req.Subtotal,
		UserId:     userID,
		ProductIds: req.ProductIDs,
	}, jsonOpt)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, resp)
}

// ── Wishlist Handlers ───────────────────────────────────────────────────────

func (h *BusinessHandler) ToggleWishlist(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}

	productID := c.Param("id")

	resp, err := h.client.ToggleWishlist(c.Request.Context(), &pb.ToggleWishlistRequest{
		UserId:    userID,
		ProductId: productID,
	}, jsonOpt)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{"is_wishlisted": resp.IsWishlisted})
}

func (h *BusinessHandler) GetWishlist(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}

	resp, err := h.client.GetWishlist(c.Request.Context(), &pb.GetWishlistRequest{UserId: userID}, jsonOpt)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, resp.Products)
}

// ── Product Q&A Handlers ───────────────────────────────────────────────────

func (h *BusinessHandler) AskProductQuestion(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}

	productID := c.Param("id")
	var req struct {
		Question string `json:"question" binding:"required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	resp, err := h.client.AskProductQuestion(c.Request.Context(), &pb.AskProductQuestionRequest{
		ProductId: productID,
		UserId:    userID,
		Question:  req.Question,
	}, jsonOpt)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, resp.Question)
}

func (h *BusinessHandler) AnswerProductQuestion(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}

	questionID := c.Param("id")
	var req struct {
		Answer string `json:"answer" binding:"required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	resp, err := h.client.AnswerProductQuestion(c.Request.Context(), &pb.AnswerProductQuestionRequest{
		QuestionId: questionID,
		AnsweredBy: userID,
		Answer:     req.Answer,
	}, jsonOpt)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{"success": resp.Success})
}

func (h *BusinessHandler) GetProductQuestions(c *gin.Context) {
	productID := c.Param("id")

	resp, err := h.client.GetProductQuestions(c.Request.Context(), &pb.GetProductQuestionsRequest{
		ProductId: productID,
	}, jsonOpt)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, resp.Questions)
}

func (h *BusinessHandler) FlagProductQuestion(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}

	questionID := c.Param("id")

	var req struct {
		Reason string `json:"reason"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	resp, err := h.client.FlagProductQuestion(c.Request.Context(), &pb.FlagProductQuestionRequest{
		QuestionId: questionID,
		UserId:     userID,
		Reason:     req.Reason,
	}, jsonOpt)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, resp)
}

func (h *BusinessHandler) ModerateProductQuestion(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}

	questionID := c.Param("id")

	var req struct {
		Action string `json:"action" binding:"required"` // approve, reject, delete
		Reason string `json:"reason"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	resp, err := h.client.ModerateProductQuestion(c.Request.Context(), &pb.ModerateProductQuestionRequest{
		QuestionId: questionID,
		BusinessId: userID,
		Action:     req.Action,
		Reason:     req.Reason,
	}, jsonOpt)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, resp)
}
