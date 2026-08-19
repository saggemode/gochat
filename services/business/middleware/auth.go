package middleware

import (
	"context"
	"strings"

	"github.com/golang-jwt/jwt/v5"
	businesserrors "gochat/services/business/errors"
	"go.uber.org/zap"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
)

// AuthConfig holds authentication configuration
type AuthConfig struct {
	JWTSecret           string
	SkipAuthMethods     map[string]bool
	RequireAdminMethods map[string]bool
}

// AuthMiddleware handles JWT authentication and authorization
type AuthMiddleware struct {
	config *AuthConfig
	logger *zap.Logger
}

// NewAuthMiddleware creates a new authentication middleware
func NewAuthMiddleware(config *AuthConfig, logger *zap.Logger) *AuthMiddleware {
	return &AuthMiddleware{
		config: config,
		logger: logger,
	}
}

// Context keys for storing user information in context
type contextKey string

const (
	UserIDKey    contextKey = "user_id"
	UserRoleKey  contextKey = "user_role"
	RequestIDKey contextKey = "request_id"
)

// Claims represents JWT claims
type Claims struct {
	UserID string `json:"user_id"`
	Role   string `json:"role"`
	jwt.RegisteredClaims
}

// Authenticate validates JWT token and extracts user information
func (m *AuthMiddleware) Authenticate(ctx context.Context, method string) (context.Context, error) {
	// Skip authentication for methods in the whitelist
	if m.config.SkipAuthMethods[method] {
		return ctx, nil
	}

	// Extract token from metadata (in gRPC, typically from metadata)
	token, err := m.extractToken(ctx)
	if err != nil {
		m.logger.Warn("Failed to extract token", 
			zap.String("method", method),
			zap.Error(err))
		return nil, status.Error(codes.Unauthenticated, "authentication required")
	}

	// Validate token
	claims, err := m.validateToken(token)
	if err != nil {
		m.logger.Warn("Invalid token",
			zap.String("method", method),
			zap.Error(err))
		return nil, status.Error(codes.Unauthenticated, "invalid token")
	}

	// Check if method requires admin role
	if m.config.RequireAdminMethods[method] && claims.Role != "admin" {
		m.logger.Warn("Insufficient permissions",
			zap.String("method", method),
			zap.String("user_id", claims.UserID),
			zap.String("role", claims.Role))
		return nil, status.Error(codes.PermissionDenied, "insufficient permissions")
	}

	// Add user information to context
	ctx = context.WithValue(ctx, UserIDKey, claims.UserID)
	ctx = context.WithValue(ctx, UserRoleKey, claims.Role)

	m.logger.Debug("Authentication successful",
		zap.String("method", method),
		zap.String("user_id", claims.UserID),
		zap.String("role", claims.Role))

	return ctx, nil
}

// extractToken extracts JWT token from context metadata
func (m *AuthMiddleware) extractToken(ctx context.Context) (string, error) {
	// In gRPC, tokens are typically sent in metadata
	// This is a simplified version - in production, you'd extract from grpc metadata
	// For now, we'll assume the token is passed in a custom header or context value
	
	// Try to get token from context (if set by upstream middleware)
	if token, ok := ctx.Value("authorization").(string); ok {
		if strings.HasPrefix(token, "Bearer ") {
			return strings.TrimPrefix(token, "Bearer "), nil
		}
		return token, nil
	}

	return "", businesserrors.UnauthorizedError("no authorization token provided")
}

// validateToken validates the JWT token and returns claims
func (m *AuthMiddleware) validateToken(tokenString string) (*Claims, error) {
	token, err := jwt.ParseWithClaims(tokenString, &Claims{}, func(token *jwt.Token) (interface{}, error) {
		// Validate signing method
		if _, ok := token.Method.(*jwt.SigningMethodHMAC); !ok {
			return nil, businesserrors.InvalidTokenError("invalid signing method")
		}
		return []byte(m.config.JWTSecret), nil
	})

	if err != nil {
		return nil, businesserrors.Wrap(err, businesserrors.ErrorCodeInvalidToken, "token validation failed")
	}

	if claims, ok := token.Claims.(*Claims); ok && token.Valid {
		return claims, nil
	}

	return nil, businesserrors.InvalidTokenError("invalid token claims")
}

// GetUserID extracts user ID from context
func GetUserID(ctx context.Context) (string, bool) {
	userID, ok := ctx.Value(UserIDKey).(string)
	return userID, ok
}

// GetUserRole extracts user role from context
func GetUserRole(ctx context.Context) (string, bool) {
	role, ok := ctx.Value(UserRoleKey).(string)
	return role, ok
}

// GetRequestID extracts request ID from context
func GetRequestID(ctx context.Context) (string, bool) {
	requestID, ok := ctx.Value(RequestIDKey).(string)
	return requestID, ok
}

// RequireAuth is a helper function to check if user is authenticated
func RequireAuth(ctx context.Context) (string, error) {
	userID, ok := GetUserID(ctx)
	if !ok || userID == "" {
		return "", businesserrors.UnauthorizedError("authentication required")
	}
	return userID, nil
}

// RequireAdmin is a helper function to check if user has admin role
func RequireAdmin(ctx context.Context) error {
	role, ok := GetUserRole(ctx)
	if !ok || role != "admin" {
		return businesserrors.PermissionDeniedError("admin role required")
	}
	return nil
}

// RequireOwnership checks if the user owns the resource
func RequireOwnership(ctx context.Context, resourceOwnerID string) error {
	userID, ok := GetUserID(ctx)
	if !ok {
		return businesserrors.UnauthorizedError("authentication required")
	}

	if userID != resourceOwnerID {
		role, _ := GetUserRole(ctx)
		if role != "admin" {
			return businesserrors.PermissionDeniedError("you don't have permission to access this resource")
		}
	}

	return nil
}
