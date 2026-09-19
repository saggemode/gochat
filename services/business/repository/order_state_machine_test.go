package repository

import (
	"testing"
)

func TestOrderStateMachine_Transitions(t *testing.T) {
	// 1. Shipped -> OutForDelivery should be allowed
	if !CanTransitionTo(OrderStatusShipped, OrderStatusOutForDelivery) {
		t.Errorf("expected transition from %s to %s to be valid", OrderStatusShipped, OrderStatusOutForDelivery)
	}

	// 2. OutForDelivery -> Delivered should be allowed
	if !CanTransitionTo(OrderStatusOutForDelivery, OrderStatusDelivered) {
		t.Errorf("expected transition from %s to %s to be valid", OrderStatusOutForDelivery, OrderStatusDelivered)
	}

	// 3. OutForDelivery -> Returned should be allowed
	if !CanTransitionTo(OrderStatusOutForDelivery, OrderStatusReturned) {
		t.Errorf("expected transition from %s to %s to be valid", OrderStatusOutForDelivery, OrderStatusReturned)
	}

	// 4. Pending -> Delivered directly should NOT be allowed
	if CanTransitionTo(OrderStatusPending, OrderStatusDelivered) {
		t.Errorf("expected transition from %s to %s to be invalid", OrderStatusPending, OrderStatusDelivered)
	}

	// 5. Delivered -> OutForDelivery should NOT be allowed
	if CanTransitionTo(OrderStatusDelivered, OrderStatusOutForDelivery) {
		t.Errorf("expected transition from %s to %s to be invalid", OrderStatusDelivered, OrderStatusOutForDelivery)
	}

	// 6. Test GetValidTransitions
	transitions := GetValidTransitions(OrderStatusOutForDelivery)
	if len(transitions) == 0 {
		t.Errorf("expected valid transitions for %s, got 0", OrderStatusOutForDelivery)
	}

	foundDelivered := false
	for _, tr := range transitions {
		if tr == OrderStatusDelivered {
			foundDelivered = true
			break
		}
	}
	if !foundDelivered {
		t.Errorf("expected %s to be in valid transitions of %s", OrderStatusDelivered, OrderStatusOutForDelivery)
	}
}
