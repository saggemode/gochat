package engine

import (
	"fmt"
	"regexp"
	"strconv"
	"strings"

	"github.com/Knetic/govaluate"
)

var varRegex = regexp.MustCompile(`\b(subject|resource|env)\.([a-zA-Z0-9_]+)\b`)

// Evaluate parses and evaluates a policy condition against attributes using govaluate.
func Evaluate(condition string, attrs map[string]string) (bool, error) {
	condition = strings.TrimSpace(condition)
	if condition == "" || condition == "true" {
		return true, nil
	}
	if condition == "false" {
		return false, nil
	}

	// 1. Sanitize the expression to replace dot notation (e.g. subject.id) with underscores (e.g. subject_id)
	// because govaluate treats dot notation as nested property access.
	sanitizedExpr := varRegex.ReplaceAllString(condition, "${1}_${2}")

	// 2. Compile the govaluate expression
	expr, err := govaluate.NewEvaluableExpression(sanitizedExpr)
	if err != nil {
		return false, fmt.Errorf("parsing condition %q: %w", condition, err)
	}

	// 3. Prepare parameters, sanitizing keys in the same way and converting values to proper types
	parameters := make(map[string]interface{}, len(attrs))
	for k, v := range attrs {
		sanitizedKey := varRegex.ReplaceAllString(k, "${1}_${2}")
		parameters[sanitizedKey] = convertValue(v)
	}

	// 4. Evaluate expression
	result, err := expr.Evaluate(parameters)
	if err != nil {
		return false, fmt.Errorf("evaluating condition %q: %w", condition, err)
	}

	// 5. Convert result to boolean
	boolVal, ok := result.(bool)
	if !ok {
		return false, fmt.Errorf("condition evaluated to non-boolean result: %T (%v)", result, result)
	}

	return boolVal, nil
}

func convertValue(v string) interface{} {
	v = strings.TrimSpace(v)
	if v == "true" {
		return true
	}
	if v == "false" {
		return false
	}
	// try numeric
	if f, err := strconv.ParseFloat(v, 64); err == nil {
		return f
	}
	// default string, strip any quotes
	return strings.Trim(v, `"'`)
}
