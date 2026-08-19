package repository

import (
	"testing"
	"time"
)

func TestIsWithinBusinessHours_Standard(t *testing.T) {
	// Create a fixed Wednesday at 14:30 (2:30 PM) UTC
	wednesday2PM := time.Date(2026, 8, 19, 14, 30, 0, 0, time.UTC) // Aug 19, 2026 is Wednesday (weekday 3)

	// Business hours: Mon-Fri (1,2,3,4,5), 09:00 - 17:00
	days := []int32{1, 2, 3, 4, 5}
	inHours := IsWithinBusinessHours("UTC", "09:00", "17:00", days, wednesday2PM)
	if !inHours {
		t.Errorf("expected wednesday 14:30 to be within 09:00-17:00 business hours")
	}

	// Test outside hours (20:00)
	wednesday8PM := time.Date(2026, 8, 19, 20, 0, 0, 0, time.UTC)
	inHours = IsWithinBusinessHours("UTC", "09:00", "17:00", days, wednesday8PM)
	if inHours {
		t.Errorf("expected wednesday 20:00 to be outside 09:00-17:00 business hours")
	}

	// Test weekend (Sunday, Aug 16, 2026)
	sunday2PM := time.Date(2026, 8, 16, 14, 0, 0, 0, time.UTC)
	inHours = IsWithinBusinessHours("UTC", "09:00", "17:00", days, sunday2PM)
	if inHours {
		t.Errorf("expected sunday 14:00 to be outside Mon-Fri business hours")
	}
}

func TestIsWithinBusinessHours_Overnight(t *testing.T) {
	// Overnight hours: 22:00 to 06:00
	days := []int32{1, 2, 3, 4, 5}

	// 23:30 (inside overnight hours)
	wednesday11PM := time.Date(2026, 8, 19, 23, 30, 0, 0, time.UTC)
	inHours := IsWithinBusinessHours("UTC", "22:00", "06:00", days, wednesday11PM)
	if !inHours {
		t.Errorf("expected 23:30 to be within 22:00-06:00 overnight hours")
	}

	// 04:30 (inside overnight hours)
	wednesday4AM := time.Date(2026, 8, 19, 4, 30, 0, 0, time.UTC)
	inHours = IsWithinBusinessHours("UTC", "22:00", "06:00", days, wednesday4AM)
	if !inHours {
		t.Errorf("expected 04:30 to be within 22:00-06:00 overnight hours")
	}

	// 12:00 (outside overnight hours)
	wednesdayNoon := time.Date(2026, 8, 19, 12, 0, 0, 0, time.UTC)
	inHours = IsWithinBusinessHours("UTC", "22:00", "06:00", days, wednesdayNoon)
	if inHours {
		t.Errorf("expected 12:00 to be outside 22:00-06:00 overnight hours")
	}
}

func TestComputeIsActiveNow_AwayMessage(t *testing.T) {
	days := []int32{1, 2, 3, 4, 5}

	// Wednesday at 20:00 (closed)
	closedTime := time.Date(2026, 8, 19, 20, 0, 0, 0, time.UTC)

	// "outside_business_hours" rule should be ACTIVE when closed
	isActiveNow := ComputeIsActiveNow("outside_business_hours", "UTC", "09:00", "17:00", days, true, closedTime)
	if !isActiveNow {
		t.Errorf("expected away message to be active when business is closed")
	}

	// Wednesday at 14:00 (open)
	openTime := time.Date(2026, 8, 19, 14, 0, 0, 0, time.UTC)
	isActiveNow = ComputeIsActiveNow("outside_business_hours", "UTC", "09:00", "17:00", days, true, openTime)
	if isActiveNow {
		t.Errorf("expected away message to be INACTIVE when business is open")
	}
}
