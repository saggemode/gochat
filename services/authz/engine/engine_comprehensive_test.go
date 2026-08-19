package engine

import (
	"testing"
)

func TestEvaluate_EmptyAndLiterals(t *testing.T) {
	tests := []struct {
		name      string
		condition string
		attrs     map[string]string
		want      bool
		wantErr   bool
	}{
		{"empty is true", "", nil, true, false},
		{"whitespace is true", "  ", nil, true, false},
		{"true literal", "true", nil, true, false},
		{"false literal", "false", nil, false, false},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got, err := Evaluate(tt.condition, tt.attrs)
			if (err != nil) != tt.wantErr {
				t.Errorf("Evaluate() error = %v, wantErr %v", err, tt.wantErr)
			}
			if got != tt.want {
				t.Errorf("Evaluate() = %v, want %v", got, tt.want)
			}
		})
	}
}

func TestEvaluate_Equality(t *testing.T) {
	tests := []struct {
		name      string
		condition string
		attrs     map[string]string
		want      bool
		wantErr   bool
	}{
		{
			"equal match",
			"subject.id == resource.sender_id",
			map[string]string{"subject.id": "abc", "resource.sender_id": "abc"},
			true, false,
		},
		{
			"equal mismatch",
			"subject.id == resource.sender_id",
			map[string]string{"subject.id": "abc", "resource.sender_id": "xyz"},
			false, false,
		},
		{
			"not equal match",
			"subject.id != resource.sender_id",
			map[string]string{"subject.id": "abc", "resource.sender_id": "xyz"},
			true, false,
		},
		{
			"not equal mismatch",
			"subject.id != resource.sender_id",
			map[string]string{"subject.id": "abc", "resource.sender_id": "abc"},
			false, false,
		},
		{
			"missing attribute returns error",
			"subject.id == resource.sender_id",
			map[string]string{"subject.id": "abc"},
			false, true,
		},
		{
			"both missing attributes returns error",
			"subject.id == resource.sender_id",
			map[string]string{},
			false, true,
		},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got, err := Evaluate(tt.condition, tt.attrs)
			if (err != nil) != tt.wantErr {
				t.Errorf("Evaluate() error = %v, wantErr %v", err, tt.wantErr)
			}
			if got != tt.want {
				t.Errorf("Evaluate() = %v, want %v", got, tt.want)
			}
		})
	}
}

func TestEvaluate_NumericComparisons(t *testing.T) {
	tests := []struct {
		name      string
		condition string
		attrs     map[string]string
		want      bool
		wantErr   bool
	}{
		{"less than true", "resource.age_sec < 60", map[string]string{"resource.age_sec": "30"}, true, false},
		{"less than false", "resource.age_sec < 60", map[string]string{"resource.age_sec": "90"}, false, false},
		{"less equal true", "resource.age_sec <= 60", map[string]string{"resource.age_sec": "60"}, true, false},
		{"greater than true", "resource.age_sec > 60", map[string]string{"resource.age_sec": "90"}, true, false},
		{"greater than false", "resource.age_sec > 60", map[string]string{"resource.age_sec": "30"}, false, false},
		{"greater equal true", "resource.age_sec >= 60", map[string]string{"resource.age_sec": "60"}, true, false},
		{"mixed numeric types", "resource.value > 3.14", map[string]string{"resource.value": "3.5"}, true, false},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got, err := Evaluate(tt.condition, tt.attrs)
			if (err != nil) != tt.wantErr {
				t.Errorf("Evaluate() error = %v, wantErr %v", err, tt.wantErr)
			}
			if got != tt.want {
				t.Errorf("Evaluate() = %v, want %v", got, tt.want)
			}
		})
	}
}

func TestEvaluate_LogicalAND(t *testing.T) {
	attrs := map[string]string{
		"subject.role":      "admin",
		"subject.id":        "user_1",
		"resource.owner_id": "user_1",
	}

	tests := []struct {
		name      string
		condition string
		want      bool
	}{
		{"both true", "subject.role == 'admin' && subject.id == resource.owner_id", true},
		{"first false", "subject.role == 'user' && subject.id == resource.owner_id", false},
		{"second false", "subject.role == 'admin' && subject.id == 'other'", false},
		{"both false", "subject.role == 'user' && subject.id == 'other'", false},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got, err := Evaluate(tt.condition, attrs)
			if err != nil {
				t.Fatalf("unexpected error: %v", err)
			}
			if got != tt.want {
				t.Errorf("Evaluate() = %v, want %v", got, tt.want)
			}
		})
	}
}

func TestEvaluate_LogicalOR(t *testing.T) {
	attrs := map[string]string{
		"subject.conversation_role": "member",
		"subject.system_role":       "banned",
	}

	tests := []struct {
		name      string
		condition string
		want      bool
	}{
		{"first matches", "subject.conversation_role == 'banned' || subject.system_role == 'banned'", true},
		{"second matches", "subject.system_role == 'banned' || subject.conversation_role == 'banned'", true},
		{"neither matches", "subject.conversation_role == 'admin' || subject.system_role == 'user'", false},
		{"both mismatch", "subject.conversation_role == 'admin' || subject.system_role == 'user'", false},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got, err := Evaluate(tt.condition, attrs)
			if err != nil {
				t.Fatalf("unexpected error: %v", err)
			}
			if got != tt.want {
				t.Errorf("Evaluate() = %v, want %v", got, tt.want)
			}
		})
	}
}

func TestEvaluate_CombinedANDOR(t *testing.T) {
	attrs := map[string]string{
		"subject.role":      "user",
		"subject.id":        "user_999",
		"env.active":        "true",
		"resource.owner_id": "user_999",
	}

	condition := "subject.role == 'admin' || subject.id == resource.owner_id && env.active == true"
	got, err := Evaluate(condition, attrs)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	// OR splits first: ["subject.role == 'admin'", "subject.id == resource.owner_id && env.active == true"]
	// First OR part: false. Second OR part: AND of (true, true) = true. Result: true.
	if got != true {
		t.Errorf("Evaluate() = %v, want true", got)
	}
}

func TestEvaluate_InvalidExpression(t *testing.T) {
	_, err := Evaluate("subject.role === 'admin'", map[string]string{"subject.role": "admin"})
	if err == nil {
		t.Error("expected error for === operator, got nil")
	}
}

func TestEvaluate_QuotedValues(t *testing.T) {
	attrs := map[string]string{"subject.id": "user_1"}
	got, err := Evaluate("subject.id == 'user_1'", attrs)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if got != true {
		t.Errorf("Evaluate(quoted) = %v, want true", got)
	}
}

func TestEvaluate_EnvPrefix(t *testing.T) {
	attrs := map[string]string{"env.version": "2"}
	got, err := Evaluate("env.version == 2", attrs)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if got != true {
		t.Errorf("Evaluate(env) = %v, want true", got)
	}
}

func TestEvaluate_EnvPrefixWithAttr(t *testing.T) {
	attrs := map[string]string{"env.active": "true", "subject.role": "admin"}
	got, err := Evaluate("subject.role == 'admin' && env.active == true", attrs)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if got != true {
		t.Errorf("Evaluate(env+attr) = %v, want true", got)
	}
}

func TestEvaluate_LiteralComparison(t *testing.T) {
	attrs := map[string]string{"resource.count": "5"}
	got, err := Evaluate("resource.count > 3", attrs)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if got != true {
		t.Errorf("Evaluate(literal) = %v, want true", got)
	}
}
