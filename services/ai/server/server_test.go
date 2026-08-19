package server

import (
	"context"
	"testing"

	"go.uber.org/zap"

	aipb "gochat/gen/ai"
)

func TestAIServer_Methods(t *testing.T) {
	log := zap.NewNop()
	srv := NewAIServer(nil, log)
	ctx := context.Background()

	t.Run("SummarizeChat", func(t *testing.T) {
		resp, err := srv.SummarizeChat(ctx, &aipb.SummarizeChatRequest{
			ConversationId: "conv-123",
			Language:       "en",
		})
		if err != nil {
			t.Fatalf("SummarizeChat failed: %v", err)
		}
		if resp == nil || resp.Summary == "" {
			t.Fatalf("expected summary, got empty")
		}
	})

	t.Run("SuggestReplies", func(t *testing.T) {
		resp, err := srv.SuggestReplies(ctx, &aipb.SuggestRepliesRequest{
			ConversationId: "conv-123",
			Count:          3,
		})
		if err != nil {
			t.Fatalf("SuggestReplies failed: %v", err)
		}
		if len(resp.Suggestions) == 0 {
			t.Fatalf("expected suggestions, got none")
		}
	})

	t.Run("TranslateMessage", func(t *testing.T) {
		resp, err := srv.TranslateMessage(ctx, &aipb.TranslateMessageRequest{
			Text:           "hello",
			TargetLanguage: "spanish",
		})
		if err != nil {
			t.Fatalf("TranslateMessage failed: %v", err)
		}
		if resp.TranslatedText != "Hola" {
			t.Errorf("expected Hola, got %s", resp.TranslatedText)
		}
	})

	t.Run("AdjustTone", func(t *testing.T) {
		resp, err := srv.AdjustTone(ctx, &aipb.AdjustToneRequest{
			Text: "hey thanks for the update asap",
			Tone: "formal",
		})
		if err != nil {
			t.Fatalf("AdjustTone failed: %v", err)
		}
		if resp.AdjustedText == "" {
			t.Errorf("expected adjusted text, got empty")
		}
	})

	t.Run("ExtractActionItems", func(t *testing.T) {
		resp, err := srv.ExtractActionItems(ctx, &aipb.ExtractActionItemsRequest{
			ConversationId: "conv-123",
		})
		if err != nil {
			t.Fatalf("ExtractActionItems failed: %v", err)
		}
		if resp == nil {
			t.Fatalf("expected action items response")
		}
	})
}
