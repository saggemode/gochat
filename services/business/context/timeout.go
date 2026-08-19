package contextx

import (
	"context"
	"time"

	businesserrors "gochat/services/business/errors"
	"go.uber.org/zap"
)

// TimeoutConfig holds timeout configuration for different operations
type TimeoutConfig struct {
	DefaultTimeout      time.Duration
	DatabaseTimeout     time.Duration
	ExternalAPITimeout time.Duration
	LongRunningTimeout  time.Duration
}

// DefaultTimeoutConfig returns default timeout configurations
func DefaultTimeoutConfig() *TimeoutConfig {
	return &TimeoutConfig{
		DefaultTimeout:      30 * time.Second,
		DatabaseTimeout:     10 * time.Second,
		ExternalAPITimeout:  15 * time.Second,
		LongRunningTimeout:  5 * time.Minute,
	}
}

// WithTimeout adds a timeout to context with proper error handling
func WithTimeout(ctx context.Context, timeout time.Duration, operation string, logger *zap.Logger) (context.Context, context.CancelFunc) {
	ctx, cancel := context.WithTimeout(ctx, timeout)
	
	// Start a goroutine to log when timeout occurs
	go func() {
		<-ctx.Done()
		if ctx.Err() == context.DeadlineExceeded {
			logger.Warn("Operation timed out",
				zap.String("operation", operation),
				zap.Duration("timeout", timeout))
		}
	}()
	
	return ctx, cancel
}

// WithDatabaseTimeout creates a context with database operation timeout
func WithDatabaseTimeout(ctx context.Context, config *TimeoutConfig, logger *zap.Logger) (context.Context, context.CancelFunc) {
	return WithTimeout(ctx, config.DatabaseTimeout, "database_operation", logger)
}

// WithExternalAPITimeout creates a context with external API call timeout
func WithExternalAPITimeout(ctx context.Context, config *TimeoutConfig, logger *zap.Logger) (context.Context, context.CancelFunc) {
	return WithTimeout(ctx, config.ExternalAPITimeout, "external_api_call", logger)
}

// WithLongRunningTimeout creates a context for long-running operations
func WithLongRunningTimeout(ctx context.Context, config *TimeoutConfig, logger *zap.Logger) (context.Context, context.CancelFunc) {
	return WithTimeout(ctx, config.LongRunningTimeout, "long_running_operation", logger)
}

// CheckTimeout checks if context has timed out and returns appropriate error
func CheckTimeout(ctx context.Context) error {
	select {
	case <-ctx.Done():
		if ctx.Err() == context.DeadlineExceeded {
			return businesserrors.TimeoutError("operation timed out")
		}
		return ctx.Err()
	default:
		return nil
	}
}

// RunWithTimeout executes a function with a timeout
func RunWithTimeout(ctx context.Context, timeout time.Duration, operation string, fn func() error, logger *zap.Logger) error {
	ctx, cancel := WithTimeout(ctx, timeout, operation, logger)
	defer cancel()
	
	resultChan := make(chan error, 1)
	
	go func() {
		resultChan <- fn()
	}()
	
	select {
	case err := <-resultChan:
		return err
	case <-ctx.Done():
		return businesserrors.TimeoutError(operation + " timed out")
	}
}
