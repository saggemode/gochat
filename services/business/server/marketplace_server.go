package server

import (
	"context"

	pb "gochat/gen/business"
	"gochat/services/business/repository"

	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
)

func (s *BusinessServer) CreateMarketplaceProduct(ctx context.Context, req *pb.CreateMarketplaceProductRequest) (*pb.CreateMarketplaceProductResponse, error) {
	p, err := s.repo.CreateMarketplaceProduct(ctx, &repository.MarketplaceProduct{
		BusinessID:      req.BusinessId,
		OwnerID:         req.OwnerId,
		Name:            req.Name,
		Description:     req.Description,
		CategoryID:      req.CategoryId,
		SubCategoryID:   req.SubCategoryId,
		BrandID:         req.BrandId,
		Price:           req.Price,
		DiscountPercent: req.DiscountPercent,
		Currency:        req.Currency,
		Quantity:        req.Quantity,
		SKU:             req.Sku,
		Color:           req.Color,
		Size:            req.Size,
		Weight:          req.Weight,
		ShippingFee:     req.ShippingFee,
		ImageURLs:       req.ImageUrls,
	})
	if err != nil {
		return nil, status.Errorf(codes.Internal, "create marketplace product: %v", err)
	}
	return &pb.CreateMarketplaceProductResponse{Product: marketplaceProductToPB(p)}, nil
}

func (s *BusinessServer) UpdateMarketplaceProduct(ctx context.Context, req *pb.UpdateMarketplaceProductRequest) (*pb.UpdateMarketplaceProductResponse, error) {
	p, err := s.repo.UpdateMarketplaceProduct(ctx, &repository.MarketplaceProduct{
		ID:              req.Id,
		OwnerID:         req.OwnerId,
		Name:            req.Name,
		Description:     req.Description,
		CategoryID:      req.CategoryId,
		SubCategoryID:   req.SubCategoryId,
		BrandID:         req.BrandId,
		Price:           req.Price,
		DiscountPercent: req.DiscountPercent,
		Currency:        req.Currency,
		Quantity:        req.Quantity,
		SKU:             req.Sku,
		Color:           req.Color,
		Size:            req.Size,
		Weight:          req.Weight,
		ShippingFee:     req.ShippingFee,
		IsPublished:     req.IsPublished,
		ImageURLs:       req.ImageUrls,
	})
	if err != nil {
		return nil, status.Errorf(codes.Internal, "update marketplace product: %v", err)
	}
	return &pb.UpdateMarketplaceProductResponse{Product: marketplaceProductToPB(p)}, nil
}

func (s *BusinessServer) DeleteMarketplaceProduct(ctx context.Context, req *pb.DeleteMarketplaceProductRequest) (*pb.DeleteMarketplaceProductResponse, error) {
	if err := s.repo.DeleteMarketplaceProduct(ctx, req.Id, req.OwnerId); err != nil {
		return nil, status.Errorf(codes.Internal, "delete marketplace product: %v", err)
	}
	return &pb.DeleteMarketplaceProductResponse{Success: true}, nil
}

func (s *BusinessServer) GetMarketplaceProduct(ctx context.Context, req *pb.GetMarketplaceProductRequest) (*pb.GetMarketplaceProductResponse, error) {
	p, err := s.repo.GetMarketplaceProduct(ctx, req.Id)
	if err != nil {
		return nil, status.Errorf(codes.NotFound, "product not found: %v", err)
	}
	return &pb.GetMarketplaceProductResponse{Product: marketplaceProductToPB(p)}, nil
}

func (s *BusinessServer) ListBusinessProducts(ctx context.Context, req *pb.ListBusinessProductsRequest) (*pb.ListBusinessProductsResponse, error) {
	limit := req.Limit
	if limit <= 0 {
		limit = 20
	}
	products, total, err := s.repo.ListBusinessProducts(ctx, req.BusinessId, limit, req.Offset)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "list business products: %v", err)
	}
	var pbProducts []*pb.MarketplaceProduct
	for _, p := range products {
		pbProducts = append(pbProducts, marketplaceProductToPB(p))
	}
	return &pb.ListBusinessProductsResponse{Products: pbProducts, Total: total}, nil
}

func (s *BusinessServer) ListMarketplaceProducts(ctx context.Context, req *pb.ListMarketplaceProductsRequest) (*pb.ListMarketplaceProductsResponse, error) {
	limit := req.Limit
	if limit <= 0 {
		limit = 20
	}
	products, total, err := s.repo.ListMarketplaceProducts(ctx, req.CategoryId, req.SortBy, req.Search, limit, req.Offset)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "list marketplace products: %v", err)
	}
	var pbProducts []*pb.MarketplaceProduct
	for _, p := range products {
		pbProducts = append(pbProducts, marketplaceProductToPB(p))
	}
	return &pb.ListMarketplaceProductsResponse{Products: pbProducts, Total: total}, nil
}

func (s *BusinessServer) ListCategories(ctx context.Context, req *pb.ListCategoriesRequest) (*pb.ListCategoriesResponse, error) {
	categories, err := s.repo.ListCategories(ctx)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "list categories: %v", err)
	}
	var pbCategories []*pb.Category
	for _, c := range categories {
		var pbSubs []*pb.SubCategory
		for _, sc := range c.SubCategories {
			pbSubs = append(pbSubs, &pb.SubCategory{
				Id: sc.ID, CategoryId: sc.CategoryID, Name: sc.Name, SortOrder: sc.SortOrder,
			})
		}
		pbCategories = append(pbCategories, &pb.Category{
			Id: c.ID, Name: c.Name, Icon: c.Icon, SortOrder: c.SortOrder, SubCategories: pbSubs,
		})
	}
	return &pb.ListCategoriesResponse{Categories: pbCategories}, nil
}

func (s *BusinessServer) GetStore(ctx context.Context, req *pb.GetStoreRequest) (*pb.GetStoreResponse, error) {
	store, err := s.repo.GetStore(ctx, req.Slug, req.UserId)
	if err != nil {
		return nil, status.Errorf(codes.NotFound, "store not found: %v", err)
	}
	var pbProducts []*pb.MarketplaceProduct
	for _, p := range store.Products {
		pbProducts = append(pbProducts, marketplaceProductToPB(p))
	}
	return &pb.GetStoreResponse{
		Store: &pb.Store{
			Profile:      profileToPB(store.Profile),
			Products:     pbProducts,
			ProductCount: store.ProductCount,
			AvgRating:    store.AvgRating,
			TotalReviews: store.TotalReviews,
		},
	}, nil
}

func (s *BusinessServer) TrackProductView(ctx context.Context, req *pb.TrackProductViewRequest) (*pb.TrackProductViewResponse, error) {
	_ = s.repo.TrackProductView(ctx, req.ProductId, req.UserId)
	return &pb.TrackProductViewResponse{Success: true}, nil
}

func (s *BusinessServer) CreateReview(ctx context.Context, req *pb.CreateReviewRequest) (*pb.CreateReviewResponse, error) {
	if req.Rating < 1 || req.Rating > 5 {
		return nil, status.Errorf(codes.InvalidArgument, "rating must be between 1 and 5")
	}
	r, err := s.repo.CreateReview(ctx, req.ProductId, req.UserId, req.Rating, req.Comment)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "create review: %v", err)
	}
	return &pb.CreateReviewResponse{Review: reviewToPB(r)}, nil
}

func (s *BusinessServer) ListReviews(ctx context.Context, req *pb.ListReviewsRequest) (*pb.ListReviewsResponse, error) {
	limit := req.Limit
	if limit <= 0 {
		limit = 20
	}
	reviews, total, err := s.repo.ListReviews(ctx, req.ProductId, limit, req.Offset)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "list reviews: %v", err)
	}
	var pbReviews []*pb.Review
	for _, r := range reviews {
		pbReviews = append(pbReviews, reviewToPB(r))
	}
	return &pb.ListReviewsResponse{Reviews: pbReviews, Total: total}, nil
}

func marketplaceProductToPB(p *repository.MarketplaceProduct) *pb.MarketplaceProduct {
	if p == nil {
		return nil
	}
	return &pb.MarketplaceProduct{
		Id:              p.ID,
		BusinessId:      p.BusinessID,
		OwnerId:         p.OwnerID,
		Name:            p.Name,
		Description:     p.Description,
		CategoryId:      p.CategoryID,
		SubCategoryId:   p.SubCategoryID,
		BrandId:         p.BrandID,
		Price:           p.Price,
		DiscountPercent: p.DiscountPercent,
		Currency:        p.Currency,
		Quantity:        p.Quantity,
		Sku:             p.SKU,
		Color:           p.Color,
		Size:            p.Size,
		Weight:          p.Weight,
		ShippingFee:     p.ShippingFee,
		IsPublished:     p.IsPublished,
		ViewCount:       p.ViewCount,
		OrderCount:      p.OrderCount,
		RatingAvg:       p.RatingAvg,
		ReviewCount:     p.ReviewCount,
		ImageUrls:       p.ImageURLs,
		SellerName:      p.SellerName,
		SellerAvatar:    p.SellerAvatar,
		SellerSlug:      p.SellerSlug,
		CategoryName:    p.CategoryName,
		BrandName:       p.BrandName,
		CreatedAt:       p.CreatedAt.Format("2006-01-02T15:04:05Z07:00"),
	}
}

func reviewToPB(r *repository.Review) *pb.Review {
	if r == nil {
		return nil
	}
	return &pb.Review{
		Id:         r.ID,
		ProductId:  r.ProductID,
		UserId:     r.UserID,
		UserName:   r.UserName,
		UserAvatar: r.UserAvatar,
		Rating:     r.Rating,
		Comment:    r.Comment,
		CreatedAt:  r.CreatedAt.Format("2006-01-02T15:04:05Z07:00"),
	}
}

// ── Product Variant Handlers ─────────────────────────────────────────────────

func (s *BusinessServer) CreateProductVariant(ctx context.Context, req *pb.CreateProductVariantRequest) (*pb.CreateProductVariantResponse, error) {
	if req.ProductId == "" {
		return nil, status.Error(codes.InvalidArgument, "product_id is required")
	}
	var priceOverride *float64
	if req.PriceOverride > 0 {
		priceOverride = &req.PriceOverride
	}
	v, err := s.repo.CreateProductVariant(ctx, &repository.ProductVariant{
		ProductID:      req.ProductId,
		SKU:            req.Sku,
		Title:          req.Title,
		AttributesJSON: req.AttributesJson,
		PriceOverride:  priceOverride,
		StockQuantity:  req.StockQuantity,
		ImageURL:       req.ImageUrl,
		IsActive:       req.IsActive,
	})
	if err != nil {
		return nil, status.Errorf(codes.Internal, "create product variant: %v", err)
	}
	return &pb.CreateProductVariantResponse{Variant: productVariantToPB(v)}, nil
}

func (s *BusinessServer) ListProductVariants(ctx context.Context, req *pb.ListProductVariantsRequest) (*pb.ListProductVariantsResponse, error) {
	if req.ProductId == "" {
		return nil, status.Error(codes.InvalidArgument, "product_id is required")
	}
	variants, err := s.repo.ListProductVariants(ctx, req.ProductId)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "list product variants: %v", err)
	}
	pbVariants := make([]*pb.ProductVariant, len(variants))
	for i, v := range variants {
		pbVariants[i] = productVariantToPB(v)
	}
	return &pb.ListProductVariantsResponse{Variants: pbVariants}, nil
}

func (s *BusinessServer) UpdateProductVariant(ctx context.Context, req *pb.UpdateProductVariantRequest) (*pb.UpdateProductVariantResponse, error) {
	if req.Id == "" || req.ProductId == "" {
		return nil, status.Error(codes.InvalidArgument, "id and product_id are required")
	}
	var priceOverride *float64
	if req.PriceOverride > 0 {
		priceOverride = &req.PriceOverride
	}
	v, err := s.repo.UpdateProductVariant(ctx, &repository.ProductVariant{
		ID:             req.Id,
		ProductID:      req.ProductId,
		SKU:            req.Sku,
		Title:          req.Title,
		AttributesJSON: req.AttributesJson,
		PriceOverride:  priceOverride,
		StockQuantity:  req.StockQuantity,
		ImageURL:       req.ImageUrl,
		IsActive:       req.IsActive,
	})
	if err != nil {
		return nil, status.Errorf(codes.Internal, "update product variant: %v", err)
	}
	return &pb.UpdateProductVariantResponse{Variant: productVariantToPB(v)}, nil
}

func (s *BusinessServer) DeleteProductVariant(ctx context.Context, req *pb.DeleteProductVariantRequest) (*pb.DeleteProductVariantResponse, error) {
	if req.Id == "" || req.ProductId == "" {
		return nil, status.Error(codes.InvalidArgument, "id and product_id are required")
	}
	err := s.repo.DeleteProductVariant(ctx, req.Id, req.ProductId)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "delete product variant: %v", err)
	}
	return &pb.DeleteProductVariantResponse{Success: true}, nil
}

func productVariantToPB(v *repository.ProductVariant) *pb.ProductVariant {
	if v == nil {
		return nil
	}
	var priceOverride float64
	if v.PriceOverride != nil {
		priceOverride = *v.PriceOverride
	}
	return &pb.ProductVariant{
		Id:             v.ID,
		ProductId:      v.ProductID,
		Sku:            v.SKU,
		Title:          v.Title,
		AttributesJson: v.AttributesJSON,
		PriceOverride:  priceOverride,
		StockQuantity:  v.StockQuantity,
		ImageUrl:       v.ImageURL,
		IsActive:       v.IsActive,
	}
}
