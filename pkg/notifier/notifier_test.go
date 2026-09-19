package notifier

import (
	"context"
	"strconv"
	"testing"
	"time"

	"go.uber.org/zap"

	"gochat/pkg/config"
)

func TestGenerateSecureCode(t *testing.T) {
	for i := 0; i < 100; i++ {
		code, err := GenerateSecureCode()
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if len(code) != 6 {
			t.Fatalf("expected 6 digits, got %s", code)
		}
		num, err := strconv.Atoi(code)
		if err != nil {
			t.Fatalf("expected integer string, got %s", code)
		}
		if num < 100000 || num > 999999 {
			t.Fatalf("code out of bounds: %d", num)
		}
	}
}

func TestMaskEmail(t *testing.T) {
	tests := []struct {
		input    string
		expected string
	}{
		{"john.doe@example.com", "j***e@example.com"},
		{"a@test.com", "a***@test.com"},
		{"ab@test.com", "a***@test.com"},
		{"alex@company.org", "a***x@company.org"},
		{"notanemail", "notanemail"},
	}

	for _, tt := range tests {
		got := MaskEmail(tt.input)
		if got != tt.expected {
			t.Errorf("MaskEmail(%q) = %q; want %q", tt.input, got, tt.expected)
		}
	}
}

func TestMaskPhone(t *testing.T) {
	tests := []struct {
		input    string
		expected string
	}{
		{"+2348012345678", "+234 *** **78"},
		{"+15551234567", "+155 *** **67"},
		{"12345", "12345"},
	}

	for _, tt := range tests {
		got := MaskPhone(tt.input)
		if got != tt.expected {
			t.Errorf("MaskPhone(%q) = %q; want %q", tt.input, got, tt.expected)
		}
	}
}

func TestMockNotifierDispatch(t *testing.T) {
	cfg := &config.Config{
		AppEnv: "test",
	}
	n := New(cfg, zap.NewNop())

	err := n.SendRecoveryEmail(context.Background(), RecoveryRequest{
		RecipientEmail: "test@example.com",
		DisplayName:    "Test User",
		RecoveryCode:   "123456",
		ExpiresIn:      15 * time.Minute,
	})
	if err != nil {
		t.Fatalf("SendRecoveryEmail failed: %v", err)
	}

	err = n.SendRecoverySMS(context.Background(), RecoveryRequest{
		RecipientPhone: "+15550001234",
		RecoveryCode:   "123456",
		ExpiresIn:      15 * time.Minute,
	})
	if err != nil {
		t.Fatalf("SendRecoverySMS failed: %v", err)
	}
}
