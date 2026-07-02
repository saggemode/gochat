package jwtutil

import (
	"testing"
	"time"
)

func TestGenerateAccessToken(t *testing.T) {
	m := NewManager("test-secret-key", 1*time.Hour, 7*24*time.Hour)

	token, err := m.GenerateAccessToken("user-123", "user@test.com")
	if err != nil {
		t.Fatalf("GenerateAccessToken() error = %v", err)
	}
	if token == "" {
		t.Fatal("GenerateAccessToken() returned empty token")
	}
}

func TestGenerateRefreshToken(t *testing.T) {
	m := NewManager("test-secret-key", 1*time.Hour, 7*24*time.Hour)

	token, err := m.GenerateRefreshToken("user-123", "user@test.com")
	if err != nil {
		t.Fatalf("GenerateRefreshToken() error = %v", err)
	}
	if token == "" {
		t.Fatal("GenerateRefreshToken() returned empty token")
	}
}

func TestGenerateTokenPair(t *testing.T) {
	m := NewManager("test-secret-key", 1*time.Hour, 7*24*time.Hour)

	access, refresh, err := m.GenerateTokenPair("user-123", "user@test.com")
	if err != nil {
		t.Fatalf("GenerateTokenPair() error = %v", err)
	}
	if access == "" {
		t.Fatal("GenerateTokenPair() returned empty access token")
	}
	if refresh == "" {
		t.Fatal("GenerateTokenPair() returned empty refresh token")
	}
}

func TestValidateAccess_Valid(t *testing.T) {
	m := NewManager("test-secret-key", 1*time.Hour, 7*24*time.Hour)

	token, _ := m.GenerateAccessToken("user-123", "user@test.com")
	claims, err := m.ValidateAccess(token)
	if err != nil {
		t.Fatalf("ValidateAccess() error = %v", err)
	}
	if claims.UserID != "user-123" {
		t.Errorf("UserID = %q, want %q", claims.UserID, "user-123")
	}
	if claims.Email != "user@test.com" {
		t.Errorf("Email = %q, want %q", claims.Email, "user@test.com")
	}
	if claims.TokenType != AccessToken {
		t.Errorf("TokenType = %q, want %q", claims.TokenType, AccessToken)
	}
}

func TestValidateRefresh_Valid(t *testing.T) {
	m := NewManager("test-secret-key", 1*time.Hour, 7*24*time.Hour)

	token, _ := m.GenerateRefreshToken("user-456", "refresh@test.com")
	claims, err := m.ValidateRefresh(token)
	if err != nil {
		t.Fatalf("ValidateRefresh() error = %v", err)
	}
	if claims.UserID != "user-456" {
		t.Errorf("UserID = %q, want %q", claims.UserID, "user-456")
	}
	if claims.TokenType != RefreshToken {
		t.Errorf("TokenType = %q, want %q", claims.TokenType, RefreshToken)
	}
}

func TestValidateAccess_RefreshTokenRejected(t *testing.T) {
	m := NewManager("test-secret-key", 1*time.Hour, 7*24*time.Hour)

	token, _ := m.GenerateRefreshToken("user-123", "user@test.com")
	_, err := m.ValidateAccess(token)
	if err != ErrWrongTokenType {
		t.Errorf("ValidateAccess(refreshToken) error = %v, want %v", err, ErrWrongTokenType)
	}
}

func TestValidateRefresh_AccessTokenRejected(t *testing.T) {
	m := NewManager("test-secret-key", 1*time.Hour, 7*24*time.Hour)

	token, _ := m.GenerateAccessToken("user-123", "user@test.com")
	_, err := m.ValidateRefresh(token)
	if err != ErrWrongTokenType {
		t.Errorf("ValidateRefresh(accessToken) error = %v, want %v", err, ErrWrongTokenType)
	}
}

func TestValidate_InvalidToken(t *testing.T) {
	m := NewManager("test-secret-key", 1*time.Hour, 7*24*time.Hour)

	_, err := m.Validate("invalid-token-string")
	if err != ErrTokenInvalid {
		t.Errorf("Validate(invalid) error = %v, want %v", err, ErrTokenInvalid)
	}
}

func TestValidate_WrongSecret(t *testing.T) {
	m1 := NewManager("secret-one", 1*time.Hour, 7*24*time.Hour)
	m2 := NewManager("secret-two", 1*time.Hour, 7*24*time.Hour)

	token, _ := m1.GenerateAccessToken("user-123", "user@test.com")
	_, err := m2.ValidateAccess(token)
	if err != ErrTokenInvalid {
		t.Errorf("ValidateAccess(wrongSecret) error = %v, want %v", err, ErrTokenInvalid)
	}
}

func TestValidate_ExpiredToken(t *testing.T) {
	m := NewManager("test-secret-key", 1*time.Nanosecond, 1*time.Nanosecond)

	token, _ := m.GenerateAccessToken("user-123", "user@test.com")
	time.Sleep(10 * time.Millisecond)
	_, err := m.ValidateAccess(token)
	if err != ErrTokenExpired {
		t.Errorf("ValidateAccess(expired) error = %v, want %v", err, ErrTokenExpired)
	}
}

func TestValidate_ClaimsFields(t *testing.T) {
	m := NewManager("test-secret-key", 1*time.Hour, 7*24*time.Hour)

	token, _ := m.GenerateAccessToken("user-abc", "abc@test.com")
	claims, err := m.ValidateAccess(token)
	if err != nil {
		t.Fatalf("ValidateAccess() error = %v", err)
	}
	if claims.Issuer != "gochat" {
		t.Errorf("Issuer = %q, want %q", claims.Issuer, "gochat")
	}
	if claims.Subject != "user-abc" {
		t.Errorf("Subject = %q, want %q", claims.Subject, "user-abc")
	}
	if claims.ID == "" {
		t.Error("JTI should not be empty")
	}
}
