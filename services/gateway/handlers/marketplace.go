package handlers

import (
	"net/http"
	"strconv"

	pb "gochat/gen/business"

	"github.com/gin-gonic/gin"
)

// ── Seller Marketplace Product Handlers ─────────────────────────────────────

func (h *BusinessHandler) CreateMarketplaceProduct(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}

	var req struct {
		Name            string   `json:"name" binding:"required"`
		Description     string   `json:"description"`
		CategoryID      string   `json:"category_id"`
		SubCategoryID   string   `json:"sub_category_id"`
		BrandID         string   `json:"brand_id"`
		Price           float64  `json:"price" binding:"required"`
		DiscountPercent float64  `json:"discount_percent"`
		Currency        string   `json:"currency"`
		Quantity        int32    `json:"quantity"`
		SKU             string   `json:"sku"`
		Color           string   `json:"color"`
		Size            string   `json:"size"`
		Weight          float64  `json:"weight"`
		ShippingFee     float64  `json:"shipping_fee"`
		ImageURLs       []string `json:"image_urls"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	resp, err := h.client.CreateMarketplaceProduct(c.Request.Context(), &pb.CreateMarketplaceProductRequest{
		BusinessId:      userID,
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
		ImageUrls:       req.ImageURLs,
	}, jsonOpt)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusCreated, resp.Product)
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

	c.JSON(http.StatusOK, resp.Product)
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

	resp, err := h.client.ListBusinessProducts(c.Request.Context(), &pb.ListBusinessProductsRequest{
		BusinessId: userID,
		Limit:      int32(limit),
		Offset:     int32(offset),
	}, jsonOpt)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"products": resp.Products,
		"total":    resp.Total,
	})
}

// ── Public Marketplace Feed Handlers ────────────────────────────────────────

func (h *BusinessHandler) ListMarketplaceProducts(c *gin.Context) {
	categoryID := c.Query("category_id")
	sortBy := c.DefaultQuery("sort_by", "best_selling")
	search := c.Query("search")
	limit, _ := strconv.Atoi(c.DefaultQuery("limit", "20"))
	offset, _ := strconv.Atoi(c.DefaultQuery("offset", "0"))

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

	c.JSON(http.StatusOK, gin.H{
		"products": resp.Products,
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

	c.JSON(http.StatusOK, resp.Product)
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
		Rating  int32  `json:"rating" binding:"required"`
		Comment string `json:"comment"`
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
	}, jsonOpt)
	if err != nil {
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
