package engine

import (
	"fmt"
	"strconv"
	"strings"
)

// Evaluate parses and evaluates a simple policy condition against attributes.
// Supported syntax:
// - "key == value"
// - "key != value"
// - "key < value"
// - "key <= value"
// - "key > value"
// - "key >= value"
// - Combine multiple expressions with " && " or " || " (supports standard logical precedence).
func Evaluate(condition string, attrs map[string]string) (bool, error) {
	condition = strings.TrimSpace(condition)
	if condition == "" || condition == "true" {
		return true, nil
	}
	if condition == "false" {
		return false, nil
	}

	// Handle OR logic (lowest precedence)
	orParts := strings.Split(condition, " || ")
	for _, orPart := range orParts {
		// Handle AND logic inside each OR block
		andParts := strings.Split(orPart, " && ")
		andMatch := true
		for _, andPart := range andParts {
			match, err := evaluateSingle(strings.TrimSpace(andPart), attrs)
			if err != nil {
				return false, err
			}
			if !match {
				andMatch = false
				break
			}
		}
		if andMatch {
			return true, nil
		}
	}

	return false, nil
}

func evaluateSingle(expr string, attrs map[string]string) (bool, error) {
	// Order of operators list matches length descending to avoid prefix matching issues (e.g. <= matched as <)
	operators := []string{"==", "!=", "<=", ">=", "<", ">"}
	var op string
	var parts []string
	for _, candidate := range operators {
		if idx := strings.Index(expr, candidate); idx != -1 {
			op = candidate
			parts = []string{
				strings.TrimSpace(expr[:idx]),
				strings.TrimSpace(expr[idx+len(candidate):]),
			}
			break
		}
	}

	if op == "" || len(parts) != 2 {
		return false, fmt.Errorf("invalid expression structure: %s", expr)
	}

	left := resolveVal(parts[0], attrs)
	right := resolveVal(parts[1], attrs)

	// Handle numeric values
	leftNum, errLeft := strconv.ParseFloat(left, 64)
	rightNum, errRight := strconv.ParseFloat(right, 64)

	if errLeft == nil && errRight == nil {
		switch op {
		case "==":
			return leftNum == rightNum, nil
		case "!=":
			return leftNum != rightNum, nil
		case "<":
			return leftNum < rightNum, nil
		case "<=":
			return leftNum <= rightNum, nil
		case ">":
			return leftNum > rightNum, nil
		case ">=":
			return leftNum >= rightNum, nil
		}
	}

	// Remove any literal quotes
	left = strings.Trim(left, `"'`)
	right = strings.Trim(right, `"'`)

	switch op {
	case "==":
		return left == right, nil
	case "!=":
		return left != right, nil
	case "<":
		return left < right, nil
	case "<=":
		return left <= right, nil
	case ">":
		return left > right, nil
	case ">=":
		return left >= right, nil
	}

	return false, fmt.Errorf("unsupported operator: %s", op)
}

func resolveVal(token string, attrs map[string]string) string {
	if strings.HasPrefix(token, "subject.") || strings.HasPrefix(token, "resource.") || strings.HasPrefix(token, "env.") {
		if val, ok := attrs[token]; ok {
			return val
		}
		return ""
	}
	return token
}
