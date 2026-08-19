package logging

import (
	"context"
	"time"

	"github.com/google/uuid"
	"go.uber.org/zap"
)

// Logger wraps zap.Logger with additional context support
type Logger struct {
	*zap.Logger
}

// NewLogger creates a new structured logger
func NewLogger(baseLogger *zap.Logger) *Logger {
	return &Logger{Logger: baseLogger}
}

// WithRequestID adds a request ID to the logger
func (l *Logger) WithRequestID(requestID string) *Logger {
	return &Logger{l.Logger.With(zap.String("request_id", requestID))}
}

// WithUserID adds a user ID to the logger
func (l *Logger) WithUserID(userID string) *Logger {
	return &Logger{l.Logger.With(zap.String("user_id", userID))}
}

// WithOperation adds an operation name to the logger
func (l *Logger) WithOperation(operation string) *Logger {
	return &Logger{l.Logger.With(zap.String("operation", operation))}
}

// WithDuration adds duration to the logger
func (l *Logger) WithDuration(duration time.Duration) *Logger {
	return &Logger{l.Logger.With(zap.Duration("duration_ms", duration))}
}

// WithFields adds multiple fields to the logger
func (l *Logger) WithFields(fields ...zap.Field) *Logger {
	return &Logger{l.Logger.With(fields...)}
}

// LogRequest logs an incoming request with context
func (l *Logger) LogRequest(ctx context.Context, method string, req interface{}) {
	requestID := GetRequestID(ctx)
	userID := GetUserID(ctx)

	logger := l.WithRequestID(requestID).WithUserID(userID).WithOperation(method)

	logger.Info("Request received",
		zap.String("method", method),
		zap.Any("request", req))
}

// LogResponse logs a response with context
func (l *Logger) LogResponse(ctx context.Context, method string, resp interface{}, duration time.Duration) {
	requestID := GetRequestID(ctx)
	userID := GetUserID(ctx)

	logger := l.WithRequestID(requestID).WithUserID(userID).WithOperation(method).WithDuration(duration)

	logger.Info("Response sent",
		zap.String("method", method),
		zap.Any("response", resp))
}

// LogError logs an error with context
func (l *Logger) LogError(ctx context.Context, method string, err error, duration time.Duration) {
	requestID := GetRequestID(ctx)
	userID := GetUserID(ctx)

	logger := l.WithRequestID(requestID).WithUserID(userID).WithOperation(method).WithDuration(duration)

	logger.Error("Request failed",
		zap.String("method", method),
		zap.Error(err))
}

// LogDatabaseOperation logs a database operation
func (l *Logger) LogDatabaseOperation(ctx context.Context, operation string, duration time.Duration) {
	requestID := GetRequestID(ctx)
	userID := GetUserID(ctx)

	logger := l.WithRequestID(requestID).WithUserID(userID).WithOperation(operation).WithDuration(duration)

	logger.Debug("Database operation completed",
		zap.String("operation", operation))
}

// LogBusinessEvent logs a business event
func (l *Logger) LogBusinessEvent(ctx context.Context, eventType string, details map[string]interface{}) {
	requestID := GetRequestID(ctx)
	userID := GetUserID(ctx)

	logger := l.WithRequestID(requestID).WithUserID(userID)

	fields := []zap.Field{zap.String("event_type", eventType)}
	for k, v := range details {
		fields = append(fields, zap.Any(k, v))
	}

	logger.Info("Business event", fields...)
}

// Context keys
type contextKey string

const (
	requestIDKey contextKey = "request_id"
	userIDKey    contextKey = "user_id"
)

// NewRequestID generates a new request ID
func NewRequestID() string {
	return uuid.New().String()
}

// WithRequestID adds a request ID to context
func WithRequestID(ctx context.Context, requestID string) context.Context {
	return context.WithValue(ctx, requestIDKey, requestID)
}

// WithUserID adds a user ID to context
func WithUserID(ctx context.Context, userID string) context.Context {
	return context.WithValue(ctx, userIDKey, userID)
}

// GetRequestID extracts request ID from context
func GetRequestID(ctx context.Context) string {
	if requestID, ok := ctx.Value(requestIDKey).(string); ok {
		return requestID
	}
	return ""
}

// GetUserID extracts user ID from context
func GetUserID(ctx context.Context) string {
	if userID, ok := ctx.Value(userIDKey).(string); ok {
		return userID
	}
	return ""
}

// OperationTimer tracks operation duration
type OperationTimer struct {
	startTime time.Time
	logger    *Logger
	method    string
}

// NewOperationTimer creates a new operation timer
func NewOperationTimer(logger *Logger, method string) *OperationTimer {
	return &OperationTimer{
		startTime: time.Now(),
		logger:    logger,
		method:    method,
	}
}

// End logs the operation completion with duration
func (t *OperationTimer) End(ctx context.Context, resp interface{}, err error) {
	duration := time.Since(t.startTime)

	if err != nil {
		t.logger.LogError(ctx, t.method, err, duration)
	} else {
		t.logger.LogResponse(ctx, t.method, resp, duration)
	}
}

// Duration returns the elapsed time
func (t *OperationTimer) Duration() time.Duration {
	return time.Since(t.startTime)
}
