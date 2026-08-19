package repository

// OrderStatus represents the current state of an order
type OrderStatus string

const (
	OrderStatusPending    OrderStatus = "pending"     // Order created, awaiting payment
	OrderStatusPaid       OrderStatus = "paid"        // Payment confirmed, awaiting processing
	OrderStatusProcessing OrderStatus = "processing"  // Being prepared for shipment
	OrderStatusShipped    OrderStatus = "shipped"     // Shipped to customer
	OrderStatusDelivered  OrderStatus = "delivered"   // Delivered to customer
	OrderStatusCancelled  OrderStatus = "cancelled"   // Order cancelled
	OrderStatusRefunded   OrderStatus = "refunded"    // Order refunded
	OrderStatusReturned   OrderStatus = "returned"    // Order returned by customer
)

// OrderStatusTransition represents a valid transition between order statuses
type OrderStatusTransition struct {
	From OrderStatus
	To   OrderStatus
}

// ValidOrderStatusTransitions defines the allowed state transitions
var ValidOrderStatusTransitions = map[OrderStatus][]OrderStatus{
	OrderStatusPending: {
		OrderStatusPaid,      // Payment received
		OrderStatusCancelled, // Customer cancelled
	},
	OrderStatusPaid: {
		OrderStatusProcessing, // Start processing
		OrderStatusCancelled,  // Customer cancelled before processing
		OrderStatusRefunded,    // Refund requested
	},
	OrderStatusProcessing: {
		OrderStatusShipped,    // Order shipped
		OrderStatusCancelled,  // Cancel before shipping
		OrderStatusRefunded,    // Refund during processing
	},
	OrderStatusShipped: {
		OrderStatusDelivered,  // Order delivered
		OrderStatusReturned,   // Customer returned
		OrderStatusRefunded,   // Partial/full refund
	},
	OrderStatusDelivered: {
		OrderStatusReturned, // Customer returned after delivery
		OrderStatusRefunded, // Refund after delivery
	},
	OrderStatusCancelled: {}, // Terminal state
	OrderStatusRefunded: {},  // Terminal state
	OrderStatusReturned: {
		OrderStatusRefunded, // Return processed as refund
	},
}

// CanTransitionTo checks if a transition from one status to another is valid
func CanTransitionTo(from, to OrderStatus) bool {
	validTransitions, exists := ValidOrderStatusTransitions[from]
	if !exists {
		return false
	}
	
	for _, validStatus := range validTransitions {
		if validStatus == to {
			return true
		}
	}
	return false
}

// GetValidTransitions returns all valid next statuses for a given current status
func GetValidTransitions(current OrderStatus) []OrderStatus {
	transitions, exists := ValidOrderStatusTransitions[current]
	if !exists {
		return []OrderStatus{}
	}
	return transitions
}

// IsTerminalStatus checks if a status is a terminal state (no further transitions)
func IsTerminalStatus(status OrderStatus) bool {
	validTransitions, exists := ValidOrderStatusTransitions[status]
	return !exists || len(validTransitions) == 0
}

// CanCancel checks if an order can be cancelled from its current status
func CanCancel(status OrderStatus) bool {
	return CanTransitionTo(status, OrderStatusCancelled)
}

// CanRefund checks if an order can be refunded from its current status
func CanRefund(status OrderStatus) bool {
	return CanTransitionTo(status, OrderStatusRefunded)
}

// CanModify checks if an order can be modified from its current status
func CanModify(status OrderStatus) bool {
	// Orders can only be modified before shipping
	return status == OrderStatusPending || status == OrderStatusPaid || status == OrderStatusProcessing
}

// OrderStatusChange represents a change in order status with metadata
type OrderStatusChange struct {
	ID           string
	OrderID      string
	FromStatus   OrderStatus
	ToStatus     OrderStatus
	ChangedBy    string // User ID who made the change
	ChangeReason string
	CreatedAt    string
}
