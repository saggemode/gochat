package mapper

import (
	"time"

	pb "gochat/gen/business"
	"gochat/services/business/repository"
)

// Mapper handles conversions between repository models and protobuf messages
type Mapper struct{}

// NewMapper creates a new mapper instance
func NewMapper() *Mapper {
	return &Mapper{}
}

// BusinessProfile converts repository BusinessProfile to protobuf BusinessProfile
func (m *Mapper) BusinessProfile(p *repository.BusinessProfile) *pb.BusinessProfile {
	if p == nil {
		return nil
	}
	return &pb.BusinessProfile{
		UserId:       p.UserID,
		BusinessName: p.BusinessName,
		Category:     p.Category,
		Description:  p.Description,
		Address:      p.Address,
		Website:      p.Website,
		Email:        p.Email,
		Phone:        p.Phone,
		HoursJson:    p.HoursJSON,
		IsVerified:   p.IsVerified,
		LogoUrl:      p.LogoURL,
		BannerUrl:    p.BannerURL,
		State:        p.State,
		CountryCode:  p.CountryCode,
		Slug:         p.Slug,
	}
}

// Product converts repository Product to protobuf Product
func (m *Mapper) Product(p *repository.Product) *pb.Product {
	if p == nil {
		return nil
	}
	return &pb.Product{
		Id:          p.ID,
		CatalogId:   p.CatalogID,
		Name:        p.Name,
		Description: p.Description,
		Price:       p.Price,
		Currency:    p.Currency,
		ImageUrl:    p.ImageURL,
		InStock:     p.InStock,
	}
}

// Products converts a slice of repository Products to protobuf Products
func (m *Mapper) Products(products []*repository.Product) []*pb.Product {
	if products == nil {
		return nil
	}
	result := make([]*pb.Product, len(products))
	for i, p := range products {
		result[i] = m.Product(p)
	}
	return result
}

// Appointment converts repository Appointment to protobuf Appointment
func (m *Mapper) Appointment(a *repository.Appointment) *pb.Appointment {
	if a == nil {
		return nil
	}
	return &pb.Appointment{
		Id:              a.ID,
		BusinessId:      a.BusinessID,
		Title:           a.Title,
		Description:     a.Description,
		StartTime:       a.StartTime.Unix(),
		EndTime:         a.EndTime.Unix(),
		MaxBookings:     int32(a.MaxBookings),
		CurrentBookings: int32(a.CurrentBookings),
	}
}

// Appointments converts a slice of repository Appointments to protobuf Appointments
func (m *Mapper) Appointments(appts []*repository.Appointment) []*pb.Appointment {
	if appts == nil {
		return nil
	}
	result := make([]*pb.Appointment, len(appts))
	for i, a := range appts {
		result[i] = m.Appointment(a)
	}
	return result
}

// Cart converts repository Cart to protobuf Cart
func (m *Mapper) Cart(c *repository.Cart) *pb.Cart {
	if c == nil {
		return nil
	}
	return &pb.Cart{
		Id:       c.ID,
		UserId:   c.UserID,
		Subtotal: c.Subtotal,
		Items:    m.CartItems(c.Items),
	}
}

// CartItem converts repository CartItem to protobuf CartItem
func (m *Mapper) CartItem(item *repository.CartItem) *pb.CartItem {
	if item == nil {
		return nil
	}
	return &pb.CartItem{
		Id:                    item.ID,
		CartId:                item.CartID,
		ProductId:             item.ProductID,
		Quantity:              item.Quantity,
		LockedPrice:           item.LockedPrice,
		LockedDiscountPercent: item.LockedDiscountPercent,
		PriceLockedAt:         item.PriceLockedAt.Format(time.RFC3339),
	}
}

// CartItems converts a slice of repository CartItems to protobuf CartItems
func (m *Mapper) CartItems(items []*repository.CartItem) []*pb.CartItem {
	if items == nil {
		return nil
	}
	result := make([]*pb.CartItem, len(items))
	for i, item := range items {
		result[i] = m.CartItem(item)
	}
	return result
}

// Order converts repository Order to protobuf Order
func (m *Mapper) Order(order *repository.Order) *pb.Order {
	if order == nil {
		return nil
	}
	return &pb.Order{
		Id:              order.ID,
		OrderNumber:     order.OrderNumber,
		BuyerId:         order.BuyerID,
		BusinessId:      order.BusinessID,
		TotalAmount:     order.TotalAmount,
		ShippingFee:     order.ShippingFee,
		DiscountAmount:  order.DiscountAmount,
		GrandTotal:      order.GrandTotal,
		CouponCode:      order.CouponCode,
		Status:          string(order.Status),
		ShippingName:    order.ShippingName,
		ShippingPhone:   order.ShippingPhone,
		ShippingAddress: order.ShippingAddress,
		ShippingCity:    order.ShippingCity,
		ShippingState:   order.ShippingState,
		ShippingCountry: order.ShippingCountry,
		PaymentMethod:   order.PaymentMethod,
		PaymentStatus:   order.PaymentStatus,
		Notes:           order.Notes,
		CreatedAt:       order.CreatedAt.Format(time.RFC3339),
		Items:           m.OrderItems(order.Items),
	}
}

// Orders converts a slice of repository Orders to protobuf Orders
func (m *Mapper) Orders(orders []*repository.Order) []*pb.Order {
	if orders == nil {
		return nil
	}
	result := make([]*pb.Order, len(orders))
	for i, order := range orders {
		result[i] = m.Order(order)
	}
	return result
}

// OrderItem converts repository OrderItem to protobuf OrderItem
func (m *Mapper) OrderItem(item *repository.OrderItem) *pb.OrderItem {
	if item == nil {
		return nil
	}
	return &pb.OrderItem{
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

// OrderItems converts a slice of repository OrderItems to protobuf OrderItems
func (m *Mapper) OrderItems(items []*repository.OrderItem) []*pb.OrderItem {
	if items == nil {
		return nil
	}
	result := make([]*pb.OrderItem, len(items))
	for i, item := range items {
		result[i] = m.OrderItem(item)
	}
	return result
}

// OrderStatusChange converts repository OrderStatusChange to protobuf OrderStatusChangePB
func (m *Mapper) OrderStatusChange(change *repository.OrderStatusChange) *pb.OrderStatusChangePB {
	if change == nil {
		return nil
	}
	return &pb.OrderStatusChangePB{
		Id:           change.ID,
		OrderId:      change.OrderID,
		FromStatus:   string(change.FromStatus),
		ToStatus:     string(change.ToStatus),
		ChangedBy:    change.ChangedBy,
		ChangeReason: change.ChangeReason,
		CreatedAt:    change.CreatedAt,
	}
}

// OrderStatusChanges converts a slice of repository OrderStatusChanges to protobuf OrderStatusChangePB
func (m *Mapper) OrderStatusChanges(changes []*repository.OrderStatusChange) []*pb.OrderStatusChangePB {
	if changes == nil {
		return nil
	}
	result := make([]*pb.OrderStatusChangePB, len(changes))
	for i, change := range changes {
		result[i] = m.OrderStatusChange(change)
	}
	return result
}

// Refund converts repository Refund to protobuf RefundPB
func (m *Mapper) Refund(refund *repository.Refund) *pb.RefundPB {
	if refund == nil {
		return nil
	}
	return &pb.RefundPB{
		Id:              refund.ID,
		OrderId:         refund.OrderID,
		RefundAmount:    refund.RefundAmount,
		RefundReason:    refund.RefundReason,
		RefundType:      refund.RefundType,
		Status:          refund.Status,
		RequestedBy:     refund.RequestedBy,
		ProcessedBy:     refund.ProcessedBy,
		ProcessedAt:     refund.ProcessedAt.Format(time.RFC3339),
		RejectionReason: refund.RejectionReason,
		RefundMethod:    refund.RefundMethod,
		RefundReference: refund.RefundReference,
		CreatedAt:       refund.CreatedAt.Format(time.RFC3339),
		Items:           m.RefundItems(refund.Items),
	}
}

// Refunds converts a slice of repository Refunds to protobuf RefundPB
func (m *Mapper) Refunds(refunds []*repository.Refund) []*pb.RefundPB {
	if refunds == nil {
		return nil
	}
	result := make([]*pb.RefundPB, len(refunds))
	for i, refund := range refunds {
		result[i] = m.Refund(refund)
	}
	return result
}

// RefundItem converts repository RefundItem to protobuf RefundItemPB
func (m *Mapper) RefundItem(item *repository.RefundItem) *pb.RefundItemPB {
	if item == nil {
		return nil
	}
	return &pb.RefundItemPB{
		Id:           item.ID,
		RefundId:     item.RefundID,
		OrderItemId:  item.OrderItemID,
		Quantity:     int32(item.Quantity),
		RefundAmount: item.RefundAmount,
		Reason:       item.Reason,
	}
}

// RefundItems converts a slice of repository RefundItems to protobuf RefundItemPB
func (m *Mapper) RefundItems(items []*repository.RefundItem) []*pb.RefundItemPB {
	if items == nil {
		return nil
	}
	result := make([]*pb.RefundItemPB, len(items))
	for i, item := range items {
		result[i] = m.RefundItem(item)
	}
	return result
}

// OrderModification converts repository OrderModification to protobuf OrderModificationPB
func (m *Mapper) OrderModification(mod *repository.OrderModification) *pb.OrderModificationPB {
	if mod == nil {
		return nil
	}
	return &pb.OrderModificationPB{
		Id:               mod.ID,
		OrderId:          mod.OrderID,
		ModificationType: mod.ModificationType,
		OldValue:         mod.OldValue,
		NewValue:         mod.NewValue,
		Reason:           mod.Reason,
		RequestedBy:      mod.RequestedBy,
		ApprovedBy:       mod.ApprovedBy,
		Status:           mod.Status,
		RejectionReason:  mod.RejectionReason,
		CreatedAt:        mod.CreatedAt.Format(time.RFC3339),
	}
}

// OrderModifications converts a slice of repository OrderModifications to protobuf OrderModificationPB
func (m *Mapper) OrderModifications(mods []*repository.OrderModification) []*pb.OrderModificationPB {
	if mods == nil {
		return nil
	}
	result := make([]*pb.OrderModificationPB, len(mods))
	for i, mod := range mods {
		result[i] = m.OrderModification(mod)
	}
	return result
}

func (m *Mapper) ProductQuestion(q *repository.ProductQuestion) *pb.ProductQuestionPB {
	if q == nil {
		return nil
	}

	answer := ""
	if q.Answer != nil {
		answer = *q.Answer
	}

	answeredBy := ""
	if q.AnsweredBy != nil {
		answeredBy = *q.AnsweredBy
	}

	flagReason := ""
	if q.FlagReason != nil {
		flagReason = *q.FlagReason
	}

	return &pb.ProductQuestionPB{
		Id:         q.ID,
		ProductId:  q.ProductID,
		UserId:     q.UserID,
		Question:   q.Question,
		Answer:     answer,
		AnsweredBy: answeredBy,
		UserName:   q.UserName,
		AnswerName: q.AnswerName,
		CreatedAt:  q.CreatedAt,
		UpdatedAt:  q.UpdatedAt,
		IsFlagged:  q.IsFlagged,
		FlagReason: flagReason,
		Status:     q.Status,
		FlagCount:  int32(q.FlagCount),
	}
}

// ProductQuestions converts a slice of repository ProductQuestions to protobuf ProductQuestionPB
func (m *Mapper) ProductQuestions(questions []*repository.ProductQuestion) []*pb.ProductQuestionPB {
	if questions == nil {
		return nil
	}
	result := make([]*pb.ProductQuestionPB, len(questions))
	for i, q := range questions {
		result[i] = m.ProductQuestion(q)
	}
	return result
}

// AutoReply converts repository AutoReply to protobuf AutoReply
func (m *Mapper) AutoReply(rule *repository.AutoReply) *pb.AutoReply {
	if rule == nil {
		return nil
	}
	return &pb.AutoReply{
		Id:           rule.ID,
		TriggerType:  rule.TriggerType,
		TriggerValue: rule.TriggerValue,
		ReplyText:    rule.ReplyText,
		IsActive:     rule.IsActive,
	}
}

// AutoReplies converts a slice of repository AutoReplies to protobuf AutoReply
func (m *Mapper) AutoReplies(rules []*repository.AutoReply) []*pb.AutoReply {
	if rules == nil {
		return nil
	}
	result := make([]*pb.AutoReply, len(rules))
	for i, rule := range rules {
		result[i] = m.AutoReply(rule)
	}
	return result
}

// ProductVariant converts repository ProductVariant to protobuf ProductVariant
func (m *Mapper) ProductVariant(v *repository.ProductVariant) *pb.ProductVariant {
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

// ProductVariants converts a slice of repository ProductVariants to protobuf ProductVariants
func (m *Mapper) ProductVariants(variants []*repository.ProductVariant) []*pb.ProductVariant {
	if variants == nil {
		return nil
	}
	result := make([]*pb.ProductVariant, len(variants))
	for i, v := range variants {
		result[i] = m.ProductVariant(v)
	}
	return result
}
