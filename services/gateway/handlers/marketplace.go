package handlers

import (
	"context"
	"encoding/json"
	"net/http"
	"strconv"
	"strings"
	"time"

	pb "gochat/gen/business"
	"gochat/pkg/fcm"

	"github.com/gin-gonic/gin"
)

// ── Seller Marketplace Product Handlers ─────────────────────────────────────

func formatProductItem(p *pb.MarketplaceProduct) map[string]interface{} {
	if p == nil {
		return nil
	}
	primaryImg := ""
	if len(p.ImageUrls) > 0 {
		primaryImg = p.ImageUrls[0]
	}
	storeName := p.SellerName
	if storeName == "" {
		storeName = "Official Store"
	}
	catName := p.CategoryName
	if catName == "" {
		catName = "General"
	}
	return map[string]interface{}{
		"id":               p.Id,
		"product_id":       p.Id,
		"name":             p.Name,
		"title":            p.Name,
		"description":      p.Description,
		"price":            p.Price,
		"discount_percent": p.DiscountPercent,
		"currency":         p.Currency,
		"stock":            p.Quantity,
		"quantity":         p.Quantity,
		"sku":              p.Sku,
		"color":            p.Color,
		"size":             p.Size,
		"weight":           p.Weight,
		"shipping_fee":     p.ShippingFee,
		"is_published":     p.IsPublished,
		"is_available":     p.IsPublished,
		"in_stock":         p.Quantity > 0,
		"view_count":       p.ViewCount,
		"order_count":      p.OrderCount,
		"rating_avg":       p.RatingAvg,
		"rating":           p.RatingAvg,
		"review_count":     p.ReviewCount,
		"reviews_count":    p.ReviewCount,
		"image_urls":       p.ImageUrls,
		"image_url":        primaryImg,
		"store_id":         p.BusinessId,
		"business_id":      p.BusinessId,
		"store_name":       storeName,
		"seller_name":      storeName,
		"seller_id":        p.OwnerId,
		"owner_id":         p.OwnerId,
		"seller_avatar":    p.SellerAvatar,
		"seller_slug":      p.SellerSlug,
		"category_id":      p.CategoryId,
		"category":         catName,
		"category_name":    catName,
		"brand_name":       p.BrandName,
		"is_verified":      true,
		"created_at":       p.CreatedAt,
	}
}

func (h *BusinessHandler) CreateMarketplaceProduct(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}

	var req struct {
		Name            string   `json:"name"`
		Title           string   `json:"title"`
		Description     string   `json:"description"`
		CategoryID      string   `json:"category_id"`
		Category        string   `json:"category"`
		SubCategoryID   string   `json:"sub_category_id"`
		BrandID         string   `json:"brand_id"`
		Price           float64  `json:"price"`
		DiscountPercent float64  `json:"discount_percent"`
		Currency        string   `json:"currency"`
		Quantity        int32    `json:"quantity"`
		SKU             string   `json:"sku"`
		Color           string   `json:"color"`
		Size            string   `json:"size"`
		Weight          float64  `json:"weight"`
		ShippingFee     float64  `json:"shipping_fee"`
		ImageURL        string   `json:"image_url"`
		ImageURLs       []string `json:"image_urls"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	prodName := strings.TrimSpace(req.Name)
	if prodName == "" {
		prodName = strings.TrimSpace(req.Title)
	}
	if prodName == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "product name or title is required"})
		return
	}

	catID := strings.TrimSpace(req.CategoryID)
	if catID == "" {
		catID = strings.TrimSpace(req.Category)
	}

	imgURLs := req.ImageURLs
	if len(imgURLs) == 0 && req.ImageURL != "" {
		imgURLs = []string{req.ImageURL}
	}

	resp, err := h.client.CreateMarketplaceProduct(c.Request.Context(), &pb.CreateMarketplaceProductRequest{
		BusinessId:      userID,
		OwnerId:         userID,
		Name:            prodName,
		Description:     req.Description,
		CategoryId:      catID,
		SubCategoryId:   req.SubCategoryID,
		BrandId:         req.BrandID,
		Price:           req.Price,
		DiscountPercent: req.DiscountPercent,
		Currency:        req.Currency,
		Quantity:        req.Quantity,
		Sku:             req.SKU,
		Color:           req.Color,
		Size:            req.Size,
		Weight:          req.Weight,
		ShippingFee:     req.ShippingFee,
		ImageUrls:       imgURLs,
	}, jsonOpt)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	if resp != nil && resp.Product != nil {
		productMap := formatProductItem(resp.Product)
		if catName := strings.TrimSpace(req.Category); catName != "" && productMap["category"] == "General" {
			productMap["category"] = catName
			productMap["category_name"] = catName
		}
		evt := map[string]interface{}{
			"type":    "new_product",
			"product": productMap,
		}

		evtJSON, _ := json.Marshal(evt)

		// Publish to Redis for ALL gateway instances (including this one) to broadcast
		if h.redis != nil {
			h.redis.Publish(c.Request.Context(), "marketplace:global", evtJSON)
		} else if h.hub != nil {
			// Fallback to local broadcast if Redis is missing
			h.hub.Broadcast(evtJSON, "")
		}

		// Dispatch high-priority FCM push notifications to all followers of this store
		storeID := resp.Product.BusinessId
		if storeID == "" {
			storeID = userID
		}
		storeName := resp.Product.SellerName
		if storeName == "" {
			storeName = "Official Store"
		}
		prodID := resp.Product.Id
		prodName := resp.Product.Name
		prodPrice := resp.Product.Price
		primaryImage := ""
		if len(resp.Product.ImageUrls) > 0 {
			primaryImage = resp.Product.ImageUrls[0]
		}

		go func() {
			pushCtx, pCancel := context.WithTimeout(context.Background(), 15*time.Second)
			defer pCancel()
			_ = fcm.NotifyStoreFollowersNewProduct(pushCtx, storeID, storeName, prodID, prodName, primaryImage, prodPrice)
		}()
	}



	c.JSON(http.StatusCreated, formatProductItem(resp.Product))
}

func (h *BusinessHandler) UpdateMarketplaceProduct(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	productID := c.Param("id")

	var req struct {
		Name            string   `json:"name"`
		Description     string   `json:"description"`
		CategoryID      string   `json:"category_id"`
		SubCategoryID   string   `json:"sub_category_id"`
		BrandID         string   `json:"brand_id"`
		Price           float64  `json:"price"`
		DiscountPercent float64  `json:"discount_percent"`
		Currency        string   `json:"currency"`
		Quantity        int32    `json:"quantity"`
		SKU             string   `json:"sku"`
		Color           string   `json:"color"`
		Size            string   `json:"size"`
		Weight          float64  `json:"weight"`
		ShippingFee     float64  `json:"shipping_fee"`
		IsPublished     *bool    `json:"is_published"`
		ImageURLs       []string `json:"image_urls"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	isPublished := true
	if req.IsPublished != nil {
		isPublished = *req.IsPublished
	}

	resp, err := h.client.UpdateMarketplaceProduct(c.Request.Context(), &pb.UpdateMarketplaceProductRequest{
		Id:              productID,
		OwnerId:         userID,
		Name:            req.Name,
		Description:     req.Description,
		CategoryId:      req.CategoryID,
		SubCategoryId:   req.SubCategoryID,
		BrandId:         req.BrandID,
		Price:           req.Price,
		DiscountPercent: req.DiscountPercent,
		Currency:        req.Currency,
		Quantity:        req.Quantity,
		Sku:             req.SKU,
		Color:           req.Color,
		Size:            req.Size,
		Weight:          req.Weight,
		ShippingFee:     req.ShippingFee,
		IsPublished:     isPublished,
		ImageUrls:       req.ImageURLs,
	}, jsonOpt)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, formatProductItem(resp.Product))
}

func (h *BusinessHandler) DeleteMarketplaceProduct(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	productID := c.Param("id")

	_, err := h.client.DeleteMarketplaceProduct(c.Request.Context(), &pb.DeleteMarketplaceProductRequest{
		Id:      productID,
		OwnerId: userID,
	}, jsonOpt)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{"success": true})
}

func (h *BusinessHandler) GetMyProducts(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}

	limit, _ := strconv.Atoi(c.DefaultQuery("limit", "20"))
	offset, _ := strconv.Atoi(c.DefaultQuery("offset", "0"))
	if pageStr := c.Query("page"); pageStr != "" && c.Query("offset") == "" {
		if page, err := strconv.Atoi(pageStr); err == nil && page > 1 {
			offset = (page - 1) * limit
		}
	}

	resp, err := h.client.ListBusinessProducts(c.Request.Context(), &pb.ListBusinessProductsRequest{
		BusinessId: userID,
		Limit:      int32(limit),
		Offset:     int32(offset),
	}, jsonOpt)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	items := make([]map[string]interface{}, 0, len(resp.Products))
	for _, p := range resp.Products {
		items = append(items, formatProductItem(p))
	}
	c.JSON(http.StatusOK, gin.H{
		"products": items,
		"total":    resp.Total,
	})
}

// ── Public Marketplace Feed Handlers ────────────────────────────────────────

func (h *BusinessHandler) ListMarketplaceProducts(c *gin.Context) {
	categoryID := c.Query("category_id")
	sortBy := c.DefaultQuery("sort_by", "newest")
	search := c.Query("search")

	limit, _ := strconv.Atoi(c.DefaultQuery("limit", "20"))
	offset, _ := strconv.Atoi(c.DefaultQuery("offset", "0"))
	if pageStr := c.Query("page"); pageStr != "" && c.Query("offset") == "" {
		if page, err := strconv.Atoi(pageStr); err == nil && page > 1 {
			offset = (page - 1) * limit
		}
	}

	resp, err := h.client.ListMarketplaceProducts(c.Request.Context(), &pb.ListMarketplaceProductsRequest{
		CategoryId: categoryID,
		SortBy:     sortBy,
		Search:     search,
		Limit:      int32(limit),
		Offset:     int32(offset),
	}, jsonOpt)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	items := make([]map[string]interface{}, 0, len(resp.Products))
	for _, p := range resp.Products {
		items = append(items, formatProductItem(p))
	}
	c.JSON(http.StatusOK, gin.H{
		"products": items,
		"total":    resp.Total,
	})
}

func (h *BusinessHandler) ListFollowedProducts(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}

	limit, _ := strconv.Atoi(c.DefaultQuery("limit", "20"))
	offset, _ := strconv.Atoi(c.DefaultQuery("offset", "0"))
	if pageStr := c.Query("page"); pageStr != "" && c.Query("offset") == "" {
		if page, err := strconv.Atoi(pageStr); err == nil && page > 1 {
			offset = (page - 1) * limit
		}
	}

	resp, err := h.client.ListFollowedMarketplaceProducts(c.Request.Context(), &pb.ListFollowedMarketplaceProductsRequest{
		UserId: userID,
		Limit:  int32(limit),
		Offset: int32(offset),
	}, jsonOpt)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	items := make([]map[string]interface{}, 0, len(resp.Products))
	for _, p := range resp.Products {
		items = append(items, formatProductItem(p))
	}
	c.JSON(http.StatusOK, gin.H{
		"products": items,
		"total":    resp.Total,
	})
}


func (h *BusinessHandler) GetMarketplaceProduct(c *gin.Context) {
	productID := c.Param("id")

	resp, err := h.client.GetMarketplaceProduct(c.Request.Context(), &pb.GetMarketplaceProductRequest{
		Id: productID,
	}, jsonOpt)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "Product not found"})
		return
	}

	c.JSON(http.StatusOK, formatProductItem(resp.Product))
}

func (h *BusinessHandler) ListCategories(c *gin.Context) {
	resp, err := h.client.ListCategories(c.Request.Context(), &pb.ListCategoriesRequest{}, jsonOpt)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, resp.Categories)
}

func (h *BusinessHandler) GetStore(c *gin.Context) {
	slug := c.Query("slug")
	userID := c.Query("user_id")
	if userID == "" {
		userID = c.Param("id")
	}

	resp, err := h.client.GetStore(c.Request.Context(), &pb.GetStoreRequest{
		Slug:   slug,
		UserId: userID,
	}, jsonOpt)
	if err != nil || resp == nil || resp.Store == nil {
		c.JSON(http.StatusOK, gin.H{"store": nil})
		return
	}

	c.JSON(http.StatusOK, resp.Store)
}

func (h *BusinessHandler) GetStoreProducts(c *gin.Context) {
	storeID := c.Param("id")
	limit, _ := strconv.Atoi(c.DefaultQuery("limit", "50"))
	offset, _ := strconv.Atoi(c.DefaultQuery("offset", "0"))

	resp, err := h.client.ListBusinessProducts(c.Request.Context(), &pb.ListBusinessProductsRequest{
		BusinessId: storeID,
		Limit:      int32(limit),
		Offset:     int32(offset),
	}, jsonOpt)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	items := make([]map[string]interface{}, 0, len(resp.Products))
	for _, p := range resp.Products {
		items = append(items, formatProductItem(p))
	}
	c.JSON(http.StatusOK, gin.H{
		"products": items,
		"total":    resp.Total,
	})
}

func (h *BusinessHandler) TrackProductView(c *gin.Context) {
	productID := c.Param("id")
	userID := c.GetString("user_id") // optional from auth

	_, _ = h.client.TrackProductView(c.Request.Context(), &pb.TrackProductViewRequest{
		ProductId: productID,
		UserId:    userID,
	}, jsonOpt)

	c.JSON(http.StatusOK, gin.H{"success": true})
}

func (h *BusinessHandler) CreateReview(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	productID := c.Param("id")

	var req struct {
		Rating    int32    `json:"rating" binding:"required"`
		Comment   string   `json:"comment"`
		ImageURLs []string `json:"image_urls"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	resp, err := h.client.CreateReview(c.Request.Context(), &pb.CreateReviewRequest{
		ProductId: productID,
		UserId:    userID,
		Rating:    req.Rating,
		Comment:   req.Comment,
		ImageUrls: req.ImageURLs,
	}, jsonOpt)
	if err != nil {
		if strings.Contains(err.Error(), "cannot review your own product") {
			c.JSON(http.StatusForbidden, gin.H{"error": "You cannot review your own product"})
			return
		}
		if strings.Contains(err.Error(), "product not found") {
			c.JSON(http.StatusNotFound, gin.H{"error": "Product not found or has been removed"})
			return
		}
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusCreated, resp.Review)
}

func (h *BusinessHandler) ListReviews(c *gin.Context) {
	productID := c.Param("id")
	limit, _ := strconv.Atoi(c.DefaultQuery("limit", "20"))
	offset, _ := strconv.Atoi(c.DefaultQuery("offset", "0"))

	resp, err := h.client.ListReviews(c.Request.Context(), &pb.ListReviewsRequest{
		ProductId: productID,
		Limit:     int32(limit),
		Offset:    int32(offset),
	}, jsonOpt)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"reviews": resp.Reviews,
		"total":   resp.Total,
	})
}

// ── Product Variant Handlers ───────────────────────────────────────────────

func (h *BusinessHandler) CreateProductVariant(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	productID := c.Param("id")

	var req struct {
		SKU            string  `json:"sku"`
		Title          string  `json:"title"`
		AttributesJSON string  `json:"attributes_json"`
		PriceOverride  float64 `json:"price_override"`
		StockQuantity  int32   `json:"stock_quantity"`
		ImageURL       string  `json:"image_url"`
		IsActive       bool    `json:"is_active"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	resp, err := h.client.CreateProductVariant(c.Request.Context(), &pb.CreateProductVariantRequest{
		ProductId:      productID,
		Sku:            req.SKU,
		Title:          req.Title,
		AttributesJson: req.AttributesJSON,
		PriceOverride:  req.PriceOverride,
		StockQuantity:  req.StockQuantity,
		ImageUrl:       req.ImageURL,
		IsActive:       req.IsActive,
	}, jsonOpt)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusCreated, resp.Variant)
}

func (h *BusinessHandler) ListProductVariants(c *gin.Context) {
	productID := c.Param("id")

	resp, err := h.client.ListProductVariants(c.Request.Context(), &pb.ListProductVariantsRequest{
		ProductId: productID,
	}, jsonOpt)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"variants": resp.Variants,
	})
}

func (h *BusinessHandler) UpdateProductVariant(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	productID := c.Param("id")
	variantID := c.Param("variantId")

	var req struct {
		SKU            string  `json:"sku"`
		Title          string  `json:"title"`
		AttributesJSON string  `json:"attributes_json"`
		PriceOverride  float64 `json:"price_override"`
		StockQuantity  int32   `json:"stock_quantity"`
		ImageURL       string  `json:"image_url"`
		IsActive       bool    `json:"is_active"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	resp, err := h.client.UpdateProductVariant(c.Request.Context(), &pb.UpdateProductVariantRequest{
		Id:             variantID,
		ProductId:      productID,
		Sku:            req.SKU,
		Title:          req.Title,
		AttributesJson: req.AttributesJSON,
		PriceOverride:  req.PriceOverride,
		StockQuantity:  req.StockQuantity,
		ImageUrl:       req.ImageURL,
		IsActive:       req.IsActive,
	}, jsonOpt)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, resp.Variant)
}

func (h *BusinessHandler) DeleteProductVariant(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	productID := c.Param("id")
	variantID := c.Param("variantId")

	_, err := h.client.DeleteProductVariant(c.Request.Context(), &pb.DeleteProductVariantRequest{
		Id:        variantID,
		ProductId: productID,
	}, jsonOpt)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{"success": true})
}

func (h *BusinessHandler) ToggleReviewHelpful(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	reviewID := c.Param("id")

	// We need to add this to the proto and business service too
	// For now, let's assume it's there
	resp, err := h.client.ToggleReviewHelpful(c.Request.Context(), &pb.ToggleReviewHelpfulRequest{
		ReviewId: reviewID, UserId: userID,
	}, jsonOpt)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, resp)
}

