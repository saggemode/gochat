package server

import (
	"context"
	"time"

	pb "gochat/gen/business"
	"gochat/services/business/repository"

	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
)

// Converters

func cartToPB(c *repository.Cart) *pb.Cart {
	if c == nil {
		return nil
	}
	pbItems := make([]*pb.CartItem, len(c.Items))
	for i, item := range c.Items {
		pbItems[i] = &pb.CartItem{
			Id:                    item.ID,
			CartId:                item.CartID,
			ProductId:             item.ProductID,
			Quantity:              item.Quantity,
			Product:               marketplaceProductToPB(item.Product),
			LockedPrice:           item.LockedPrice,
			LockedDiscountPercent: item.LockedDiscountPercent,
			PriceLockedAt:         item.PriceLockedAt.Format(time.RFC3339),
		}
	}
	return &pb.Cart{
		Id:       c.ID,
		UserId:   c.UserID,
		Items:    pbItems,
		Subtotal: c.Subtotal,
	}
}

func orderFxQuoteFromPB(fx *pb.OrderFxQuote) *repository.OrderFxQuote {
	if fx == nil {
		return nil
	}
	return &repository.OrderFxQuote{
		TargetCurrency:      fx.TargetCurrency,
		SourceCurrencies:    fx.SourceCurrencies,
		BuyerTotalAmount:    fx.BuyerTotalAmount,
		BuyerShippingFee:    fx.BuyerShippingFee,
		BuyerDiscountAmount: fx.BuyerDiscountAmount,
		BuyerGrandTotal:     fx.BuyerGrandTotal,
		Rates:               fx.Rates,
		Provider:            fx.Provider,
		AsOf:                fx.AsOf,
		Stale:               fx.Stale,
		LockedAt:            fx.LockedAt,
	}
}

func orderFxQuoteToPB(fx *repository.OrderFxQuote) *pb.OrderFxQuote {
	if fx == nil {
		return nil
	}
	return &pb.OrderFxQuote{
		TargetCurrency:      fx.TargetCurrency,
		SourceCurrencies:    fx.SourceCurrencies,
		BuyerTotalAmount:    fx.BuyerTotalAmount,
		BuyerShippingFee:    fx.BuyerShippingFee,
		BuyerDiscountAmount: fx.BuyerDiscountAmount,
		BuyerGrandTotal:     fx.BuyerGrandTotal,
		Rates:               fx.Rates,
		Provider:            fx.Provider,
		AsOf:                fx.AsOf,
		Stale:               fx.Stale,
		LockedAt:            fx.LockedAt,
	}
}

func orderToPB(o *repository.Order) *pb.Order {
	if o == nil {
		return nil
	}
	pbItems := make([]*pb.OrderItem, len(o.Items))
	for i, item := range o.Items {
		pbItems[i] = &pb.OrderItem{
			Id:                    item.ID,
			OrderId:               item.OrderID,
			ProductId:             item.ProductID,
			ProductName:           item.ProductName,
			ProductSku:            item.ProductSKU,
			ImageUrl:              item.ImageURL,
			UnitPrice:             item.UnitPrice,
			Quantity:              item.Quantity,
			Subtotal:              item.Subtotal,
			LockedPrice:           item.LockedPrice,
			LockedDiscountPercent: item.LockedDiscountPercent,
			PriceChanged:          item.PriceChanged,
		}
	}
	return &pb.Order{
		Id:                    o.ID,
		OrderNumber:           o.OrderNumber,
		BuyerId:               o.BuyerID,
		BusinessId:            o.BusinessID,
		TotalAmount:           o.TotalAmount,
		ShippingFee:           o.ShippingFee,
		DiscountAmount:        o.DiscountAmount,
		GrandTotal:            o.GrandTotal,
		CouponCode:            o.CouponCode,
		Status:                o.Status,
		ShippingName:          o.ShippingName,
		ShippingPhone:         o.ShippingPhone,
		ShippingAddress:       o.ShippingAddress,
		ShippingCity:          o.ShippingCity,
		ShippingState:         o.ShippingState,
		ShippingCountry:       o.ShippingCountry,
		PaymentMethod:         o.PaymentMethod,
		PaymentStatus:         o.PaymentStatus,
		Notes:                 o.Notes,
		BusinessName:          o.BusinessName,
		BusinessLogo:          o.BusinessLogo,
		BuyerName:             o.BuyerName,
		BuyerAvatar:           o.BuyerAvatar,
		Items:                 pbItems,
		CreatedAt:             o.CreatedAt.Format(time.RFC3339),
		FxQuote:               orderFxQuoteToPB(o.FxQuote),
		TrackingNumber:        o.TrackingNumber,
		TrackingCarrier:       o.TrackingCarrier,
		TrackingUrl:           o.TrackingURL,
		EstimatedDeliveryDate: formatTime(o.EstimatedDeliveryDate),
		ActualDeliveryDate:    formatTime(o.ActualDeliveryDate),
		ShippedAt:             formatTime(o.ShippedAt),
		DeliveryNotes:         o.DeliveryNotes,
	}
}

func formatTime(t *time.Time) string {
	if t == nil {
		return ""
	}
	return t.Format(time.RFC3339)
}

func couponToPB(c *repository.Coupon) *pb.Coupon {
	if c == nil {
		return nil
	}
	exp := ""
	if c.ExpiresAt != nil {
		exp = c.ExpiresAt.Format(time.RFC3339)
	}
	return &pb.Coupon{
		Id:             c.ID,
		BusinessId:     c.BusinessID,
		Code:           c.Code,
		DiscountType:   c.DiscountType,
		DiscountValue:  c.DiscountValue,
		MinSpend:       c.MinSpend,
		MaxUses:        c.MaxUses,
		UsedCount:      c.UsedCount,
		ExpiresAt:      exp,
		IsActive:       c.IsActive,
		CreatedAt:      c.CreatedAt.Format(time.RFC3339),
		ProductId:      c.ProductID,
		MaxUsesPerUser: c.MaxUsesPerUser,
		ProductName:    c.ProductName,
	}
}

// ── Cart Handlers ────────────────────────────────────────────────────────────

func (s *BusinessServer) AddToCart(ctx context.Context, req *pb.AddToCartRequest) (*pb.AddToCartResponse, error) {
	if req.UserId == "" || req.ProductId == "" {
		return nil, status.Error(codes.InvalidArgument, "user_id and product_id are required")
	}
	cart, err := s.repo.AddToCart(ctx, req.UserId, req.ProductId, req.Quantity, req.VariantId)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "add to cart: %v", err)
	}
	return &pb.AddToCartResponse{Cart: cartToPB(cart)}, nil
}

func (s *BusinessServer) GetCart(ctx context.Context, req *pb.GetCartRequest) (*pb.GetCartResponse, error) {
	if req.UserId == "" {
		return nil, status.Error(codes.InvalidArgument, "user_id is required")
	}
	cart, err := s.repo.GetCart(ctx, req.UserId)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "get cart: %v", err)
	}
	return &pb.GetCartResponse{Cart: cartToPB(cart)}, nil
}

func (s *BusinessServer) UpdateCartItem(ctx context.Context, req *pb.UpdateCartItemRequest) (*pb.UpdateCartItemResponse, error) {
	if req.UserId == "" || req.CartItemId == "" {
		return nil, status.Error(codes.InvalidArgument, "user_id and cart_item_id are required")
	}
	cart, err := s.repo.UpdateCartItem(ctx, req.UserId, req.CartItemId, req.Quantity)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "update cart item: %v", err)
	}
	return &pb.UpdateCartItemResponse{Cart: cartToPB(cart)}, nil
}

func (s *BusinessServer) RemoveFromCart(ctx context.Context, req *pb.RemoveFromCartRequest) (*pb.RemoveFromCartResponse, error) {
	if req.UserId == "" || req.CartItemId == "" {
		return nil, status.Error(codes.InvalidArgument, "user_id and cart_item_id are required")
	}
	cart, err := s.repo.RemoveFromCart(ctx, req.UserId, req.CartItemId)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "remove from cart: %v", err)
	}
	return &pb.RemoveFromCartResponse{Cart: cartToPB(cart)}, nil
}

func (s *BusinessServer) ClearCart(ctx context.Context, req *pb.ClearCartRequest) (*pb.ClearCartResponse, error) {
	if req.UserId == "" {
		return nil, status.Error(codes.InvalidArgument, "user_id is required")
	}
	err := s.repo.ClearCart(ctx, req.UserId)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "clear cart: %v", err)
	}
	return &pb.ClearCartResponse{Success: true}, nil
}

// ── Orders Handlers ──────────────────────────────────────────────────────────

func (s *BusinessServer) CreateOrders(ctx context.Context, req *pb.CreateOrdersRequest) (*pb.CreateOrdersResponse, error) {
	if req.BuyerId == "" || len(req.Orders) == 0 {
		return nil, status.Error(codes.InvalidArgument, "buyer_id and at least one order are required")
	}

	input := &repository.CreateOrdersInput{
		BuyerID:         req.BuyerId,
		ShippingName:    req.ShippingName,
		ShippingPhone:   req.ShippingPhone,
		ShippingAddress: req.ShippingAddress,
		ShippingCity:    req.ShippingCity,
		ShippingState:   req.ShippingState,
		ShippingCountry: req.ShippingCountry,
		PaymentMethod:   req.PaymentMethod,
		Notes:           req.Notes,
	}

	for _, oReq := range req.Orders {
		so := repository.SingleOrderInput{
			BusinessID:  oReq.BusinessId,
			CouponCode:  oReq.CouponCode,
			ShippingFee: oReq.ShippingFee,
			FxQuote:     orderFxQuoteFromPB(oReq.FxQuote),
		}
		for _, itemReq := range oReq.Items {
			so.Items = append(so.Items, struct {
				ProductID string
				Quantity  int32
			}{
				ProductID: itemReq.ProductId,
				Quantity:  itemReq.Quantity,
			})
		}
		input.Orders = append(input.Orders, so)
	}

	orders, err := s.repo.CreateOrders(ctx, input)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "create orders: %v", err)
	}

	pbOrders := make([]*pb.Order, len(orders))
	for i, ord := range orders {
		pbOrders[i] = orderToPB(ord)
	}

	return &pb.CreateOrdersResponse{Orders: pbOrders}, nil
}

func (s *BusinessServer) GetOrder(ctx context.Context, req *pb.GetOrderRequest) (*pb.GetOrderResponse, error) {
	if req.Id == "" || req.UserId == "" {
		return nil, status.Error(codes.InvalidArgument, "id and user_id are required")
	}
	ord, err := s.repo.GetOrder(ctx, req.Id, req.UserId)
	if err != nil {
		return nil, status.Errorf(codes.NotFound, "get order: %v", err)
	}
	return &pb.GetOrderResponse{Order: orderToPB(ord)}, nil
}

func (s *BusinessServer) ListBuyerOrders(ctx context.Context, req *pb.ListBuyerOrdersRequest) (*pb.ListBuyerOrdersResponse, error) {
	if req.BuyerId == "" {
		return nil, status.Error(codes.InvalidArgument, "buyer_id is required")
	}
	limit := req.Limit
	if limit <= 0 {
		limit = 20
	}
	orders, total, err := s.repo.ListBuyerOrders(ctx, req.BuyerId, limit, req.Offset)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "list buyer orders: %v", err)
	}
	pbOrders := make([]*pb.Order, len(orders))
	for i, ord := range orders {
		pbOrders[i] = orderToPB(ord)
	}
	return &pb.ListBuyerOrdersResponse{Orders: pbOrders, Total: total}, nil
}

func (s *BusinessServer) ListSellerOrders(ctx context.Context, req *pb.ListSellerOrdersRequest) (*pb.ListSellerOrdersResponse, error) {
	if req.BusinessId == "" {
		return nil, status.Error(codes.InvalidArgument, "business_id is required")
	}
	limit := req.Limit
	if limit <= 0 {
		limit = 20
	}
	orders, total, err := s.repo.ListSellerOrders(ctx, req.BusinessId, req.Status, limit, req.Offset)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "list seller orders: %v", err)
	}
	pbOrders := make([]*pb.Order, len(orders))
	for i, ord := range orders {
		pbOrders[i] = orderToPB(ord)
	}
	return &pb.ListSellerOrdersResponse{Orders: pbOrders, Total: total}, nil
}

func (s *BusinessServer) UpdateOrderStatus(ctx context.Context, req *pb.UpdateOrderStatusRequest) (*pb.UpdateOrderStatusResponse, error) {
	if req.Id == "" || req.BusinessId == "" || req.Status == "" {
		return nil, status.Error(codes.InvalidArgument, "id, business_id, and status are required")
	}
	ord, err := s.repo.UpdateOrderStatus(ctx, req.Id, req.BusinessId, req.Status)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "update order status: %v", err)
	}
	return &pb.UpdateOrderStatusResponse{Order: orderToPB(ord)}, nil
}

func (s *BusinessServer) UpdateOrderTracking(ctx context.Context, req *pb.UpdateOrderTrackingRequest) (*pb.UpdateOrderTrackingResponse, error) {
	if req.Id == "" || req.BusinessId == "" {
		return nil, status.Error(codes.InvalidArgument, "id and business_id are required")
	}

	var estimatedDeliveryDate *time.Time
	if req.EstimatedDeliveryDate != "" {
		t, err := time.Parse(time.RFC3339, req.EstimatedDeliveryDate)
		if err == nil {
			estimatedDeliveryDate = &t
		}
	}

	ord, err := s.repo.UpdateOrderTracking(ctx, req.Id, req.BusinessId, req.TrackingNumber, req.TrackingCarrier, req.TrackingUrl, req.DeliveryNotes, estimatedDeliveryDate)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "update order tracking: %v", err)
	}
	return &pb.UpdateOrderTrackingResponse{Order: orderToPB(ord)}, nil
}

// ── Coupons Handlers ────────────────────────────────────────────────────────

func (s *BusinessServer) CreateCoupon(ctx context.Context, req *pb.CreateCouponRequest) (*pb.CreateCouponResponse, error) {
	if req.BusinessId == "" || req.Code == "" {
		return nil, status.Error(codes.InvalidArgument, "business_id and code are required")
	}

	c := &repository.Coupon{
		BusinessID:     req.BusinessId,
		Code:           req.Code,
		DiscountType:   req.DiscountType,
		DiscountValue:  req.DiscountValue,
		MinSpend:       req.MinSpend,
		MaxUses:        req.MaxUses,
		ProductID:      req.ProductId,
		MaxUsesPerUser: req.MaxUsesPerUser,
	}
	if req.ExpiresAt != "" {
		t, err := time.Parse(time.RFC3339, req.ExpiresAt)
		if err == nil {
			c.ExpiresAt = &t
		}
	}

	created, err := s.repo.CreateCoupon(ctx, c)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "create coupon: %v", err)
	}

	return &pb.CreateCouponResponse{Coupon: couponToPB(created)}, nil
}

func (s *BusinessServer) ListBusinessCoupons(ctx context.Context, req *pb.ListBusinessCouponsRequest) (*pb.ListBusinessCouponsResponse, error) {
	if req.BusinessId == "" {
		return nil, status.Error(codes.InvalidArgument, "business_id is required")
	}
	coupons, err := s.repo.ListBusinessCoupons(ctx, req.BusinessId)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "list coupons: %v", err)
	}
	pbCoupons := make([]*pb.Coupon, len(coupons))
	for i, c := range coupons {
		pbCoupons[i] = couponToPB(c)
	}
	return &pb.ListBusinessCouponsResponse{Coupons: pbCoupons}, nil
}

func (s *BusinessServer) ValidateCoupon(ctx context.Context, req *pb.ValidateCouponRequest) (*pb.ValidateCouponResponse, error) {
	if req.BusinessId == "" || req.Code == "" {
		return nil, status.Error(codes.InvalidArgument, "business_id and code are required")
	}
	valid, msg, discount, coupon, err := s.repo.ValidateCoupon(ctx, req.BusinessId, req.Code, req.Subtotal, req.UserId, req.ProductIds)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "validate coupon: %v", err)
	}
	return &pb.ValidateCouponResponse{
		Valid:          valid,
		Message:        msg,
		DiscountAmount: discount,
		Coupon:         couponToPB(coupon),
	}, nil
}

// ── Wishlist Handlers ───────────────────────────────────────────────────────

func (s *BusinessServer) ToggleWishlist(ctx context.Context, req *pb.ToggleWishlistRequest) (*pb.ToggleWishlistResponse, error) {
	if req.UserId == "" || req.ProductId == "" {
		return nil, status.Error(codes.InvalidArgument, "user_id and product_id are required")
	}
	isWishlisted, err := s.repo.ToggleWishlist(ctx, req.UserId, req.ProductId)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "toggle wishlist: %v", err)
	}
	return &pb.ToggleWishlistResponse{IsWishlisted: isWishlisted}, nil
}

func (s *BusinessServer) GetWishlist(ctx context.Context, req *pb.GetWishlistRequest) (*pb.GetWishlistResponse, error) {
	if req.UserId == "" {
		return nil, status.Error(codes.InvalidArgument, "user_id is required")
	}
	products, err := s.repo.GetWishlist(ctx, req.UserId)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "get wishlist: %v", err)
	}
	pbProducts := make([]*pb.MarketplaceProduct, len(products))
	for i, p := range products {
		pbProducts[i] = marketplaceProductToPB(p)
	}
	return &pb.GetWishlistResponse{Products: pbProducts}, nil
}

// ── Product Q&A Handlers ───────────────────────────────────────────────────

func (s *BusinessServer) AskProductQuestion(ctx context.Context, req *pb.AskProductQuestionRequest) (*pb.AskProductQuestionResponse, error) {
	if req.ProductId == "" || req.UserId == "" || req.Question == "" {
		return nil, status.Error(codes.InvalidArgument, "product_id, user_id, and question are required")
	}
	q, err := s.repo.AskProductQuestion(ctx, req.ProductId, req.UserId, req.Question)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "ask question: %v", err)
	}
	return &pb.AskProductQuestionResponse{
		Question: &pb.ProductQuestionPB{
			Id:        q.ID,
			ProductId: q.ProductID,
			UserId:    q.UserID,
			Question:  q.Question,
			CreatedAt: q.CreatedAt,
			UpdatedAt: q.UpdatedAt,
		},
	}, nil
}

func (s *BusinessServer) AnswerProductQuestion(ctx context.Context, req *pb.AnswerProductQuestionRequest) (*pb.AnswerProductQuestionResponse, error) {
	if req.QuestionId == "" || req.AnsweredBy == "" || req.Answer == "" {
		return nil, status.Error(codes.InvalidArgument, "question_id, answered_by, and answer are required")
	}
	err := s.repo.AnswerProductQuestion(ctx, req.QuestionId, req.AnsweredBy, req.Answer)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "answer question: %v", err)
	}
	return &pb.AnswerProductQuestionResponse{Success: true}, nil
}

func (s *BusinessServer) GetProductQuestions(ctx context.Context, req *pb.GetProductQuestionsRequest) (*pb.GetProductQuestionsResponse, error) {
	if req.ProductId == "" {
		return nil, status.Error(codes.InvalidArgument, "product_id is required")
	}
	questions, err := s.repo.GetProductQuestions(ctx, req.ProductId)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "get questions: %v", err)
	}
	pbQuestions := make([]*pb.ProductQuestionPB, len(questions))
	for i, q := range questions {
		ans := ""
		if q.Answer != nil {
			ans = *q.Answer
		}
		answeredBy := ""
		if q.AnsweredBy != nil {
			answeredBy = *q.AnsweredBy
		}
		flagReason := ""
		if q.FlagReason != nil {
			flagReason = *q.FlagReason
		}
		pbQuestions[i] = &pb.ProductQuestionPB{
			Id:         q.ID,
			ProductId:  q.ProductID,
			UserId:     q.UserID,
			Question:   q.Question,
			Answer:     ans,
			AnsweredBy: answeredBy,
			UserName:   q.UserName,
			AnswerName: q.AnswerName,
			CreatedAt:  q.CreatedAt,
			UpdatedAt:  q.UpdatedAt,
			IsFlagged:  q.IsFlagged,
			FlagReason: flagReason,
			Status:     q.Status,
			FlagCount:  q.FlagCount,
		}
	}
	return &pb.GetProductQuestionsResponse{Questions: pbQuestions}, nil
}

func (s *BusinessServer) FlagProductQuestion(ctx context.Context, req *pb.FlagProductQuestionRequest) (*pb.FlagProductQuestionResponse, error) {
	if req.QuestionId == "" || req.UserId == "" || req.Reason == "" {
		return nil, status.Error(codes.InvalidArgument, "question_id, user_id, and reason are required")
	}
	err := s.repo.FlagProductQuestion(ctx, req.QuestionId, req.UserId, req.Reason)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "flag question: %v", err)
	}
	return &pb.FlagProductQuestionResponse{Success: true}, nil
}

func (s *BusinessServer) ModerateProductQuestion(ctx context.Context, req *pb.ModerateProductQuestionRequest) (*pb.ModerateProductQuestionResponse, error) {
	if req.QuestionId == "" || req.BusinessId == "" || req.Action == "" {
		return nil, status.Error(codes.InvalidArgument, "question_id, business_id, and action are required")
	}
	err := s.repo.ModerateProductQuestion(ctx, req.QuestionId, req.BusinessId, req.Action, req.Reason)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "moderate question: %v", err)
	}
	return &pb.ModerateProductQuestionResponse{Success: true}, nil
}

// ── Order Status Workflow ───────────────────────────────────────────────────
// TODO: Uncomment after regenerating protobuf files from business.proto

// func (s *BusinessServer) TransitionOrderStatus(ctx context.Context, req *pb.TransitionOrderStatusRequest) (*pb.TransitionOrderStatusResponse, error) {
// 	if req.OrderId == "" || req.UserId == "" || req.NewStatus == "" {
// 		return nil, status.Error(codes.InvalidArgument, "order_id, user_id, and new_status are required")
// 	}
// 	order, err := s.repo.TransitionOrderStatusWithReason(ctx, req.OrderId, req.UserId, req.NewStatus, req.Reason)
// 	if err != nil {
// 		return nil, status.Errorf(codes.Internal, "transition order status: %v", err)
// 	}
// 	return &pb.TransitionOrderStatusResponse{
// 		Order:   orderToPB(order),
// 		Success: true,
// 	}, nil
// }

// func (s *BusinessServer) GetOrderStatusHistory(ctx context.Context, req *pb.GetOrderStatusHistoryRequest) (*pb.GetOrderStatusHistoryResponse, error) {
// 	if req.OrderId == "" {
// 		return nil, status.Error(codes.InvalidArgument, "order_id is required")
// 	}
// 	history, err := s.repo.GetOrderStatusHistory(ctx, req.OrderId)
// 	if err != nil {
// 		return nil, status.Errorf(codes.Internal, "get order status history: %v", err)
// 	}
// 	pbHistory := make([]*pb.OrderStatusChangePB, len(history))
// 	for i, change := range history {
// 		pbHistory[i] = &pb.OrderStatusChangePB{
// 			Id:           change.ID,
// 			OrderId:      change.OrderID,
// 			FromStatus:   string(change.FromStatus),
// 			ToStatus:     string(change.ToStatus),
// 			ChangedBy:    change.ChangedBy,
// 			ChangeReason: change.ChangeReason,
// 			CreatedAt:    change.CreatedAt,
// 		}
// 	}
// 	return &pb.GetOrderStatusHistoryResponse{History: pbHistory}, nil
// }

// ── Refunds ───────────────────────────────────────────────────────────────
// TODO: Uncomment after regenerating protobuf files from business.proto

// func (s *BusinessServer) CreateRefund(ctx context.Context, req *pb.CreateRefundRequest) (*pb.CreateRefundResponse, error) {
// 	if req.OrderId == "" || req.UserId == "" || req.RefundAmount <= 0 || req.RefundReason == "" || req.RefundType == "" {
// 		return nil, status.Error(codes.InvalidArgument, "order_id, user_id, refund_amount, refund_reason, and refund_type are required")
// 	}
//
// 	items := make([]*repository.RefundItem, len(req.Items))
// 	for i, item := range req.Items {
// 		items[i] = &repository.RefundItem{
// 			OrderItemID:  item.OrderItemId,
// 			Quantity:     item.Quantity,
// 			RefundAmount: item.RefundAmount,
// 			Reason:       item.Reason,
// 		}
// 	}
//
// 	refund, err := s.repo.CreateRefund(ctx, req.OrderId, req.UserId, req.RefundAmount, req.RefundReason, req.RefundType, items)
// 	if err != nil {
// 		return nil, status.Errorf(codes.Internal, "create refund: %v", err)
// 	}
// 	return &pb.CreateRefundResponse{Refund: refundToPB(refund)}, nil
// }

// func (s *BusinessServer) ProcessRefund(ctx context.Context, req *pb.ProcessRefundRequest) (*pb.ProcessRefundResponse, error) {
// 	if req.RefundId == "" || req.UserId == "" || req.Status == "" {
// 		return nil, status.Error(codes.InvalidArgument, "refund_id, user_id, and status are required")
// 	}
// 	refund, err := s.repo.ProcessRefund(ctx, req.RefundId, req.UserId, req.Status, req.RejectionReason, req.RefundMethod, req.RefundReference)
// 	if err != nil {
// 		return nil, status.Errorf(codes.Internal, "process refund: %v", err)
// 	}
// 	return &pb.ProcessRefundResponse{
// 		Refund:  refundToPB(refund),
// 		Success: true,
// 	}, nil
// }

// func (s *BusinessServer) ListRefunds(ctx context.Context, req *pb.ListRefundsRequest) (*pb.ListRefundsResponse, error) {
// 	limit := req.Limit
// 	if limit <= 0 {
// 		limit = 50
// 	}
// 	offset := req.Offset
// 	if offset < 0 {
// 		offset = 0
// 	}
//
// 	refunds, total, err := s.repo.ListRefunds(ctx, req.OrderId, req.UserId, req.Status, limit, offset)
// 	if err != nil {
// 		return nil, status.Errorf(codes.Internal, "list refunds: %v", err)
// 	}
//
// 	pbRefunds := make([]*pb.RefundPB, len(refunds))
// 	for i, refund := range refunds {
// 		pbRefunds[i] = refundToPB(refund)
// 	}
//
// 	return &pb.ListRefundsResponse{
// 		Refunds: pbRefunds,
// 		Total:   total,
// 	}, nil
// }

// ── Order Modifications ───────────────────────────────────────────────────
// TODO: Uncomment after regenerating protobuf files from business.proto

// func (s *BusinessServer) CreateOrderModification(ctx context.Context, req *pb.CreateOrderModificationRequest) (*pb.CreateOrderModificationResponse, error) {
// 	if req.OrderId == "" || req.ModificationType == "" || req.UserId == "" {
// 		return nil, status.Error(codes.InvalidArgument, "order_id, modification_type, and user_id are required")
// 	}
// 	mod, err := s.repo.CreateOrderModification(ctx, req.OrderId, req.ModificationType, req.OldValue, req.NewValue, req.Reason, req.UserId)
// 	if err != nil {
// 		return nil, status.Errorf(codes.Internal, "create order modification: %v", err)
// 	}
// 	return &pb.CreateOrderModificationResponse{Modification: orderModificationToPB(mod)}, nil
// }

// func (s *BusinessServer) ProcessOrderModification(ctx context.Context, req *pb.ProcessOrderModificationRequest) (*pb.ProcessOrderModificationResponse, error) {
// 	if req.ModificationId == "" || req.UserId == "" || req.Status == "" {
// 		return nil, status.Error(codes.InvalidArgument, "modification_id, user_id, and status are required")
// 	}
// 	mod, err := s.repo.ProcessOrderModification(ctx, req.ModificationId, req.UserId, req.Status, req.RejectionReason)
// 	if err != nil {
// 		return nil, status.Errorf(codes.Internal, "process order modification: %v", err)
// 	}
// 	return &pb.ProcessOrderModificationResponse{
// 		Modification: orderModificationToPB(mod),
// 		Success:      true,
// 	}, nil
// }

// func (s *BusinessServer) ListOrderModifications(ctx context.Context, req *pb.ListOrderModificationsRequest) (*pb.ListOrderModificationsResponse, error) {
// 	limit := req.Limit
// 	if limit <= 0 {
// 		limit = 50
// 	}
// 	offset := req.Offset
// 	if offset < 0 {
// 		offset = 0
// 	}
//
// 	mods, total, err := s.repo.ListOrderModifications(ctx, req.OrderId, req.Status, limit, offset)
// 	if err != nil {
// 		return nil, status.Errorf(codes.Internal, "list order modifications: %v", err)
// 	}
//
// 	pbMods := make([]*pb.OrderModificationPB, len(mods))
// 	for i, mod := range mods {
// 		pbMods[i] = orderModificationToPB(mod)
// 	}
//
// 	return &pb.ListOrderModificationsResponse{
// 		Modifications: pbMods,
// 		Total:         total,
// 	}, nil
// }

// ── Helper Functions ─────────────────────────────────────────────────────────────
// TODO: Uncomment after regenerating protobuf files from business.proto

// func refundToPB(r *repository.Refund) *pb.RefundPB {
// 	if r == nil {
// 		return nil
// 	}
// 	items := make([]*pb.RefundItemPB, len(r.Items))
// 	for i, item := range r.Items {
// 		items[i] = &pb.RefundItemPB{
// 			Id:           item.ID,
// 			RefundId:     item.RefundID,
// 			OrderItemId:  item.OrderItemID,
// 			Quantity:     item.Quantity,
// 			RefundAmount: item.RefundAmount,
// 			Reason:       item.Reason,
// 		}
// 	}
//
// 	var processedAt string
// 	if r.ProcessedAt != nil {
// 		processedAt = r.ProcessedAt.Format(time.RFC3339)
// 	}
//
// 	return &pb.RefundPB{
// 		Id:              r.ID,
// 		OrderId:         r.OrderID,
// 		RefundAmount:    r.RefundAmount,
// 		RefundReason:    r.RefundReason,
// 		RefundType:      r.RefundType,
// 		Status:          r.Status,
// 		RequestedBy:     r.RequestedBy,
// 		ProcessedBy:     r.ProcessedBy,
// 		ProcessedAt:     processedAt,
// 		RejectionReason: r.RejectionReason,
// 		RefundMethod:    r.RefundMethod,
// 		RefundReference: r.RefundReference,
// 		CreatedAt:       r.CreatedAt.Format(time.RFC3339),
// 		Items:           items,
// 	}
// }

// func orderModificationToPB(m *repository.OrderModification) *pb.OrderModificationPB {
// 	if m == nil {
// 		return nil
// 	}
// 	return &pb.OrderModificationPB{
// 		Id:               m.ID,
// 		OrderId:          m.OrderID,
// 		ModificationType: m.ModificationType,
// 		OldValue:         m.OldValue,
// 		NewValue:         m.NewValue,
// 		Reason:           m.Reason,
// 		RequestedBy:      m.RequestedBy,
// 		ApprovedBy:       m.ApprovedBy,
// 		Status:           m.Status,
// 		RejectionReason:  m.RejectionReason,
// 		CreatedAt:        m.CreatedAt.Format(time.RFC3339),
// 	}
// }
