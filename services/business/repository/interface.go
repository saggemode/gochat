package repository

import (
	"context"
	"time"
)

// IBusinessRepository interface defines the contract for business repository operations
// This enables testing and mocking of repository implementations
type IBusinessRepository interface {
	// Business Profile Operations
	CreateBusinessProfile(ctx context.Context, profile *BusinessProfile) (*BusinessProfile, error)
	GetBusinessProfile(ctx context.Context, userID string) (*BusinessProfile, error)
	UpdateBusinessProfile(ctx context.Context, profile *BusinessProfile) (*BusinessProfile, error)
	ListBusinessProfiles(ctx context.Context, limit, offset int) ([]*BusinessProfile, int64, error)

	// Product Catalog Operations
	// Note: Catalog operations are handled in marketplace repository

	// Product Operations
	AddProduct(ctx context.Context, catalogID, name, description string, price float64, currency, imageURL string) (*Product, error)
	GetProduct(ctx context.Context, productID string) (*Product, error)
	UpdateProduct(ctx context.Context, productID string, updates map[string]interface{}) (*Product, error)
	ListProducts(ctx context.Context, catalogID string, limit, offset int) ([]*Product, int64, error)
	DeleteProduct(ctx context.Context, productID string) error

	// Appointment Operations
	CreateAppointmentSlot(ctx context.Context, businessID, title, description string, start, end time.Time, maxBookings int) (*Appointment, error)
	GetAppointment(ctx context.Context, appointmentID string) (*Appointment, error)
	BookAppointment(ctx context.Context, userID, appointmentID string, notes string) error
	ListAppointments(ctx context.Context, businessID string, limit, offset int) ([]*Appointment, int64, error)
	CancelAppointment(ctx context.Context, userID, appointmentID string) error

	// Auto Reply Operations
	SetAutoReply(ctx context.Context, userID, triggerType, triggerValue, replyText, scheduleType, timezone string, daysOfWeek []int32, startTime, endTime string) (*AutoReply, error)
	GetAutoReplies(ctx context.Context, userID string) ([]*AutoReply, error)
	DeleteAutoReply(ctx context.Context, replyID string) error

	// Queue Operations
	// Note: Queue operations are placeholder implementations

	// Cart Operations
	AddToCart(ctx context.Context, userID, productID string, quantity int32) (*Cart, error)
	GetCart(ctx context.Context, userID string) (*Cart, error)
	UpdateCartItem(ctx context.Context, userID, productID string, quantity int32) (*Cart, error)
	RemoveFromCart(ctx context.Context, userID, productID string) (*Cart, error)
	ClearCart(ctx context.Context, userID string) error

	// Order Operations
	CreateOrders(ctx context.Context, input *CreateOrdersInput) ([]*Order, error)
	GetOrder(ctx context.Context, orderID string) (*Order, error)
	GetOrderByNumber(ctx context.Context, orderNumber string) (*Order, error)
	ListBuyerOrders(ctx context.Context, userID string, limit, offset int) ([]*Order, int64, error)
	ListSellerOrders(ctx context.Context, businessID string, limit, offset int) ([]*Order, int64, error)
	UpdateOrderStatus(ctx context.Context, orderID string, status OrderStatus) error
	UpdateOrderTracking(ctx context.Context, orderID string, trackingNumber, carrier string) error

	// Order Status Workflow Operations
	TransitionOrderStatusWithReason(ctx context.Context, orderID string, newStatus OrderStatus, userID, reason string) error
	GetOrderStatusHistory(ctx context.Context, orderID string) ([]*OrderStatusChange, error)

	// Refund Operations
	CreateRefund(ctx context.Context, refund *Refund) (*Refund, error)
	GetRefund(ctx context.Context, refundID string) (*Refund, error)
	ProcessRefund(ctx context.Context, refundID string, status string, processedBy, rejectionReason, refundMethod, refundReference string) (*Refund, error)
	ListRefunds(ctx context.Context, orderID, userID, status string, limit, offset int32) ([]*Refund, int32, error)

	// Order Modification Operations
	CreateOrderModification(ctx context.Context, mod *OrderModification) (*OrderModification, error)
	GetOrderModification(ctx context.Context, modID string) (*OrderModification, error)
	ProcessOrderModification(ctx context.Context, modID string, status string, approvedBy, rejectionReason string) (*OrderModification, error)
	ListOrderModifications(ctx context.Context, orderID, status string, limit, offset int32) ([]*OrderModification, int32, error)

	// Coupon Operations
	CreateCoupon(ctx context.Context, coupon *Coupon) (*Coupon, error)
	GetCoupon(ctx context.Context, couponID string) (*Coupon, error)
	GetCouponByCode(ctx context.Context, businessID, code string) (*Coupon, error)
	ListBusinessCoupons(ctx context.Context, businessID string, limit, offset int) ([]*Coupon, int64, error)
	ValidateCoupon(ctx context.Context, businessID, code string, cartTotal float64) (*Coupon, error)
	UpdateCoupon(ctx context.Context, couponID string, updates map[string]interface{}) (*Coupon, error)
	DeleteCoupon(ctx context.Context, couponID string) error

	// Wishlist Operations
	ToggleWishlist(ctx context.Context, userID, productID string) (bool, error)
	GetWishlist(ctx context.Context, userID string, limit, offset int) ([]*Product, int64, error)
	IsInWishlist(ctx context.Context, userID, productID string) (bool, error)

	// Product Q&A Operations
	AskProductQuestion(ctx context.Context, question *ProductQuestion) (*ProductQuestion, error)
	AnswerProductQuestion(ctx context.Context, questionID, answer, answeredBy string) (*ProductQuestion, error)
	GetProductQuestions(ctx context.Context, productID string, limit, offset int) ([]*ProductQuestion, int64, error)
	FlagProductQuestion(ctx context.Context, questionID, userID, reason string) (*ProductQuestion, error)
	ModerateProductQuestion(ctx context.Context, questionID, status string) (*ProductQuestion, error)
}

// INotificationService interface defines the contract for notification operations
type INotificationService interface {
	SendPriceChangeNotification(ctx context.Context, userID string, changes []PriceChangeInfo) error
	GetUserPreferences(ctx context.Context, userID string) (*NotificationPreference, error)
	SetNotificationPreferences(ctx context.Context, prefs *NotificationPreference) error
}
