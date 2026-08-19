package errors

import (
	"fmt"
)

// Error types for better error handling and debugging

// ErrorCode represents the type of error
type ErrorCode string

const (
	// Validation errors
	ErrorCodeValidation   ErrorCode = "VALIDATION_ERROR"
	ErrorCodeInvalidInput ErrorCode = "INVALID_INPUT"

	// Authentication/Authorization errors
	ErrorCodeUnauthorized ErrorCode = "UNAUTHORIZED"
	ErrorCodeForbidden    ErrorCode = "FORBIDDEN"
	ErrorCodeInvalidToken ErrorCode = "INVALID_TOKEN"

	// Database errors
	ErrorCodeDatabase   ErrorCode = "DATABASE_ERROR"
	ErrorCodeNotFound   ErrorCode = "NOT_FOUND"
	ErrorCodeDuplicate  ErrorCode = "DUPLICATE"
	ErrorCodeConstraint ErrorCode = "CONSTRAINT_VIOLATION"

	// Business logic errors
	ErrorCodeBusiness         ErrorCode = "BUSINESS_ERROR"
	ErrorCodeInvalidState     ErrorCode = "INVALID_STATE"
	ErrorCodePermissionDenied ErrorCode = "PERMISSION_DENIED"

	// External service errors
	ErrorCodeExternalService ErrorCode = "EXTERNAL_SERVICE_ERROR"
	ErrorCodePaymentFailed   ErrorCode = "PAYMENT_FAILED"

	// System errors
	ErrorCodeInternal ErrorCode = "INTERNAL_ERROR"
	ErrorCodeTimeout  ErrorCode = "TIMEOUT"
)

// AppError represents a structured application error
type AppError struct {
	Code       ErrorCode
	Message    string
	Details    string
	Cause      error
	StatusCode int
	RequestID  string
	UserID     string
}

func (e *AppError) Error() string {
	if e.Cause != nil {
		return fmt.Sprintf("[%s] %s: %v", e.Code, e.Message, e.Cause)
	}
	return fmt.Sprintf("[%s] %s", e.Code, e.Message)
}

func (e *AppError) Unwrap() error {
	return e.Cause
}

// New creates a new AppError
func New(code ErrorCode, message string) *AppError {
	return &AppError{
		Code:    code,
		Message: message,
	}
}

// Wrap wraps an existing error with additional context
func Wrap(err error, code ErrorCode, message string) *AppError {
	return &AppError{
		Code:    code,
		Message: message,
		Cause:   err,
	}
}

// WrapWithDetails wraps an error with additional details
func WrapWithDetails(err error, code ErrorCode, message, details string) *AppError {
	return &AppError{
		Code:    code,
		Message: message,
		Details: details,
		Cause:   err,
	}
}

// WithRequestID adds request ID to the error
func (e *AppError) WithRequestID(requestID string) *AppError {
	e.RequestID = requestID
	return e
}

// WithUserID adds user ID to the error
func (e *AppError) WithUserID(userID string) *AppError {
	e.UserID = userID
	return e
}

// WithStatusCode adds HTTP status code to the error
func (e *AppError) WithStatusCode(statusCode int) *AppError {
	e.StatusCode = statusCode
	return e
}

// Common error constructors
func ValidationError(message string) *AppError {
	return New(ErrorCodeValidation, message)
}

func InvalidInputError(message string) *AppError {
	return New(ErrorCodeInvalidInput, message)
}

func UnauthorizedError(message string) *AppError {
	return New(ErrorCodeUnauthorized, message)
}

func InvalidTokenError(message string) *AppError {
	return New(ErrorCodeInvalidToken, message)
}

func ForbiddenError(message string) *AppError {
	return New(ErrorCodeForbidden, message)
}

func NotFoundError(message string) *AppError {
	return New(ErrorCodeNotFound, message)
}

func DatabaseError(err error, message string) *AppError {
	return Wrap(err, ErrorCodeDatabase, message)
}

func BusinessError(message string) *AppError {
	return New(ErrorCodeBusiness, message)
}

func InvalidStateError(message string) *AppError {
	return New(ErrorCodeInvalidState, message)
}

func PermissionDeniedError(message string) *AppError {
	return New(ErrorCodePermissionDenied, message)
}

func InternalError(err error, message string) *AppError {
	return Wrap(err, ErrorCodeInternal, message)
}

func TimeoutError(message string) *AppError {
	return New(ErrorCodeTimeout, message)
}
