package jwtutil

import (
	"errors"
	"fmt"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"github.com/google/uuid"
)

// TokenType distinguishes access tokens from refresh tokens.
type TokenType string

const (
	AccessToken  TokenType = "access"
	RefreshToken TokenType = "refresh"
)

// Claims extends JWT standard claims with our custom fields.
type Claims struct {
	UserID    string    `json:"uid"`
	Email     string    `json:"email"`
	TokenType TokenType `json:"typ"`
	jwt.RegisteredClaims
}

// Manager handles JWT generation and validation.
type Manager struct {
	secret        []byte
	accessExpiry  time.Duration
	refreshExpiry time.Duration
}

// NewManager constructs a JWT manager.
func NewManager(secret string, accessExpiry, refreshExpiry time.Duration) *Manager {
	return &Manager{
		secret:        []byte(secret),
		accessExpiry:  accessExpiry,
		refreshExpiry: refreshExpiry,
	}
}

// GenerateAccessToken creates a short-lived access token.
func (m *Manager) GenerateAccessToken(userID, email string) (string, error) {
	return m.generate(userID, email, AccessToken, m.accessExpiry)
}

// GenerateRefreshToken creates a long-lived refresh token.
func (m *Manager) GenerateRefreshToken(userID, email string) (string, error) {
	return m.generate(userID, email, RefreshToken, m.refreshExpiry)
}

// GenerateTokenPair generates both tokens in one call.
func (m *Manager) GenerateTokenPair(userID, email string) (accessToken, refreshToken string, err error) {
	accessToken, err = m.GenerateAccessToken(userID, email)
	if err != nil {
		return
	}
	refreshToken, err = m.GenerateRefreshToken(userID, email)
	return
}

// Validate parses and validates a token, returning its claims.
func (m *Manager) Validate(tokenStr string) (*Claims, error) {
	token, err := jwt.ParseWithClaims(tokenStr, &Claims{}, func(t *jwt.Token) (interface{}, error) {
		if _, ok := t.Method.(*jwt.SigningMethodHMAC); !ok {
			return nil, fmt.Errorf("unexpected signing method: %v", t.Header["alg"])
		}
		return m.secret, nil
	})
	if err != nil {
		if errors.Is(err, jwt.ErrTokenExpired) {
			return nil, ErrTokenExpired
		}
		return nil, ErrTokenInvalid
	}

	claims, ok := token.Claims.(*Claims)
	if !ok || !token.Valid {
		return nil, ErrTokenInvalid
	}

	return claims, nil
}

// ValidateAccess validates and asserts the token is an access token.
func (m *Manager) ValidateAccess(tokenStr string) (*Claims, error) {
	claims, err := m.Validate(tokenStr)
	if err != nil {
		return nil, err
	}
	if claims.TokenType != AccessToken {
		return nil, ErrWrongTokenType
	}
	return claims, nil
}

// ValidateRefresh validates and asserts the token is a refresh token.
func (m *Manager) ValidateRefresh(tokenStr string) (*Claims, error) {
	claims, err := m.Validate(tokenStr)
	if err != nil {
		return nil, err
	}
	if claims.TokenType != RefreshToken {
		return nil, ErrWrongTokenType
	}
	return claims, nil
}

// ── internal ──────────────────────────────────────────────────────────────────

func (m *Manager) generate(userID, email string, typ TokenType, expiry time.Duration) (string, error) {
	now := time.Now()
	claims := &Claims{
		UserID:    userID,
		Email:     email,
		TokenType: typ,
		RegisteredClaims: jwt.RegisteredClaims{
			ID:        uuid.New().String(),
			Subject:   userID,
			IssuedAt:  jwt.NewNumericDate(now),
			ExpiresAt: jwt.NewNumericDate(now.Add(expiry)),
			Issuer:    "gochat",
		},
	}

	token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	return token.SignedString(m.secret)
}

// ── Sentinel errors ───────────────────────────────────────────────────────────

var (
	ErrTokenExpired   = errors.New("token has expired")
	ErrTokenInvalid   = errors.New("token is invalid")
	ErrWrongTokenType = errors.New("wrong token type")
)
