package validator

import (
	"context"
	"fmt"
	"reflect"

	"github.com/go-playground/validator/v10"
	businesserrors "gochat/services/business/errors"
	"go.uber.org/zap"
)

// Validator wraps go-playground/validator for request validation
type Validator struct {
	validate *validator.Validate
	logger   *zap.Logger
}

// NewValidator creates a new validator instance
func NewValidator(logger *zap.Logger) *Validator {
	v := validator.New()
	
	// Register custom validations
	v.RegisterValidation("order_status", validateOrderStatus)
	v.RegisterValidation("refund_type", validateRefundType)
	v.RegisterValidation("refund_status", validateRefundStatus)
	v.RegisterValidation("modification_type", validateModificationType)
	v.RegisterValidation("modification_status", validateModificationStatus)
	
	return &Validator{
		validate: v,
		logger:   logger,
	}
}

// Validate validates a struct and returns an error if validation fails
func (v *Validator) Validate(ctx context.Context, req interface{}) error {
	if err := v.validate.Struct(req); err != nil {
		validationErrors, ok := err.(validator.ValidationErrors)
		if !ok {
			return businesserrors.Wrap(err, businesserrors.ErrorCodeValidation, "validation failed")
		}
		
		// Convert validation errors to a single error message
		var messages []string
		for _, ve := range validationErrors {
			messages = append(messages, fmt.Sprintf("%s: %s", ve.Field(), getErrorMessage(ve)))
		}
		
		v.logger.Warn("Validation failed",
			zap.String("request_type", reflect.TypeOf(req).String()),
			zap.Strings("errors", messages))
		
		return businesserrors.ValidationError(fmt.Sprintf("validation failed: %s", messages))
	}
	
	return nil
}

// ValidateField validates a single field
func (v *Validator) ValidateField(ctx context.Context, field interface{}, tag string) error {
	if err := v.validate.Var(field, tag); err != nil {
		return businesserrors.Wrap(err, businesserrors.ErrorCodeValidation, fmt.Sprintf("field validation failed: %s", tag))
	}
	return nil
}

// getErrorMessage returns a human-readable error message for a validation error
func getErrorMessage(ve validator.FieldError) string {
	switch ve.Tag() {
	case "required":
		return "is required"
	case "email":
		return "must be a valid email address"
	case "min":
		return fmt.Sprintf("must be at least %s", ve.Param())
	case "max":
		return fmt.Sprintf("must be at most %s", ve.Param())
	case "len":
		return fmt.Sprintf("must be %s characters long", ve.Param())
	case "gt":
		return fmt.Sprintf("must be greater than %s", ve.Param())
	case "gte":
		return fmt.Sprintf("must be greater than or equal to %s", ve.Param())
	case "lt":
		return fmt.Sprintf("must be less than %s", ve.Param())
	case "lte":
		return fmt.Sprintf("must be less than or equal to %s", ve.Param())
	case "oneof":
		return fmt.Sprintf("must be one of: %s", ve.Param())
	case "uuid":
		return "must be a valid UUID"
	case "url":
		return "must be a valid URL"
	default:
		return fmt.Sprintf("failed %s validation", ve.Tag())
	}
}

// Custom validation functions

func validateOrderStatus(fl validator.FieldLevel) bool {
	validStatuses := map[string]bool{
		"pending":     true,
		"paid":        true,
		"processing":  true,
		"shipped":     true,
		"delivered":   true,
		"cancelled":   true,
		"refunded":    true,
		"returned":    true,
	}
	return validStatuses[fl.Field().String()]
}

func validateRefundType(fl validator.FieldLevel) bool {
	validTypes := map[string]bool{
		"full":    true,
		"partial": true,
	}
	return validTypes[fl.Field().String()]
}

func validateRefundStatus(fl validator.FieldLevel) bool {
	validStatuses := map[string]bool{
		"pending":   true,
		"approved":  true,
		"rejected":  true,
		"processed": true,
		"failed":    true,
	}
	return validStatuses[fl.Field().String()]
}

func validateModificationType(fl validator.FieldLevel) bool {
	validTypes := map[string]bool{
		"shipping_address": true,
		"quantity":         true,
		"cancel_item":      true,
		"add_item":         true,
	}
	return validTypes[fl.Field().String()]
}

func validateModificationStatus(fl validator.FieldLevel) bool {
	validStatuses := map[string]bool{
		"pending":  true,
		"approved": true,
		"rejected": true,
		"applied":  true,
	}
	return validStatuses[fl.Field().String()]
}
