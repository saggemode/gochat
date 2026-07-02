package engine

import (
	"testing"
)

func TestEvaluate(t *testing.T) {
	tests := []struct {
		name      string
		condition string
		attrs     map[string]string
		want      bool
		wantErr   bool
	}{
		{
			name:      "empty condition is true",
			condition: "",
			attrs:     nil,
			want:      true,
			wantErr:   false,
		},
		{
			name:      "true is true",
			condition: "true",
			attrs:     nil,
			want:      true,
			wantErr:   false,
		},
		{
			name:      "false is false",
			condition: "false",
			attrs:     nil,
			want:      false,
			wantErr:   false,
		},
		{
			name:      "simple equality match",
			condition: "subject.id == resource.sender_id",
			attrs: map[string]string{
				"subject.id":         "user_123",
				"resource.sender_id": "user_123",
			},
			want:    true,
			wantErr: false,
		},
		{
			name:      "simple equality mismatch",
			condition: "subject.id == resource.sender_id",
			attrs: map[string]string{
				"subject.id":         "user_123",
				"resource.sender_id": "user_456",
			},
			want:    false,
			wantErr: false,
		},
		{
			name:      "simple logical OR with match",
			condition: "subject.conversation_role == 'banned' || subject.system_role == 'banned'",
			attrs: map[string]string{
				"subject.conversation_role": "member",
				"subject.system_role":       "banned",
			},
			want:    true,
			wantErr: false,
		},
		{
			name:      "simple logical OR with mismatch",
			condition: "subject.conversation_role == 'banned' || subject.system_role == 'banned'",
			attrs: map[string]string{
				"subject.conversation_role": "member",
				"subject.system_role":       "user",
			},
			want:    false,
			wantErr: false,
		},
		{
			name:      "numeric inequality comparison",
			condition: "env.time >= 1700000000 && env.time < 1800000000",
			attrs: map[string]string{
				"env.time": "1750000000",
			},
			want:    true,
			wantErr: false,
		},
		{
			name:      "nested parentheses expression",
			condition: "(subject.role == 'admin' || subject.id == resource.owner_id) && env.active == true",
			attrs: map[string]string{
				"subject.role":      "user",
				"subject.id":        "user_999",
				"resource.owner_id": "user_999",
				"env.active":        "true",
			},
			want:    true,
			wantErr: false,
		},
		{
			name:      "invalid expression syntax returns error",
			condition: "subject.role === 'admin'",
			attrs:     nil,
			want:      false,
			wantErr:   true,
		},
		{
			name:      "evaluation error due to missing variable returns error",
			condition: "subject.role == 'admin'",
			attrs:     nil,
			want:      false,
			wantErr:   true,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got, err := Evaluate(tt.condition, tt.attrs)
			if (err != nil) != tt.wantErr {
				t.Errorf("Evaluate() error = %v, wantErr %v", err, tt.wantErr)
				return
			}
			if got != tt.want {
				t.Errorf("Evaluate() got = %v, want %v", got, tt.want)
			}
		})
	}
}
