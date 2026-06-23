package repository

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
)

// AIRepository handles persistence for AI-generated data.
type AIRepository struct {
	db *pgxpool.Pool
}

// NewAIRepository constructs a repository backed by PostgreSQL.
func NewAIRepository(db *pgxpool.Pool) *AIRepository {
	return &AIRepository{db: db}
}

// Summary represents a cached AI summary.
type Summary struct {
	ID             string
	ConversationID string
	UserID         string
	SummaryText    string
	MessageCount   int
	Language       string
	CreatedAt      time.Time
}

// SmartReply represents cached smart reply suggestions.
type SmartReply struct {
	ID             string
	ConversationID string
	UserID         string
	Suggestions    []string
	ExpiresAt      time.Time
}

// SaveSummary persists an AI-generated summary.
func (r *AIRepository) SaveSummary(ctx context.Context, convID, userID, summary, lang string, msgCount int) (*Summary, error) {
	id := uuid.New().String()
	now := time.Now()

	_, err := r.db.Exec(ctx,
		`INSERT INTO ai_summaries (id, conversation_id, user_id, summary_text, message_count, language, created_at)
		 VALUES ($1, $2, $3, $4, $5, $6, $7)`,
		id, convID, userID, summary, msgCount, lang, now)
	if err != nil {
		return nil, fmt.Errorf("save summary: %w", err)
	}

	return &Summary{
		ID:             id,
		ConversationID: convID,
		UserID:         userID,
		SummaryText:    summary,
		MessageCount:   msgCount,
		Language:       lang,
		CreatedAt:      now,
	}, nil
}

// SaveSmartReplies caches smart reply suggestions.
func (r *AIRepository) SaveSmartReplies(ctx context.Context, convID, userID string, suggestions []string) error {
	id := uuid.New().String()
	sugJSON, _ := json.Marshal(suggestions)
	expires := time.Now().Add(5 * time.Minute)

	_, err := r.db.Exec(ctx,
		`INSERT INTO smart_replies (id, conversation_id, user_id, suggestions, expires_at)
		 VALUES ($1, $2, $3, $4, $5)`,
		id, convID, userID, sugJSON, expires)
	return err
}

// GetCachedReplies retrieves non-expired smart reply suggestions.
func (r *AIRepository) GetCachedReplies(ctx context.Context, convID, userID string) ([]string, error) {
	var sugJSON []byte
	err := r.db.QueryRow(ctx,
		`SELECT suggestions FROM smart_replies
		 WHERE conversation_id = $1 AND user_id = $2 AND expires_at > NOW()
		 ORDER BY created_at DESC LIMIT 1`,
		convID, userID).Scan(&sugJSON)
	if err != nil {
		return nil, err
	}

	var suggestions []string
	if err := json.Unmarshal(sugJSON, &suggestions); err != nil {
		return nil, err
	}
	return suggestions, nil
}

// ── Mock AI Engine ──────────────────────────────────────────────────────────
// In production, these would call an LLM API (Gemini, OpenAI, etc.)

// GenerateSummary produces a mock summary from message texts.
func GenerateSummary(messages []string, language string) (string, []string) {
	if len(messages) == 0 {
		return "No messages to summarize.", nil
	}

	topics := extractTopics(messages)
	summary := fmt.Sprintf("📋 Summary of %d messages:\n", len(messages))
	summary += fmt.Sprintf("• The conversation covered %d main topics.\n", len(topics))

	for i, topic := range topics {
		if i >= 5 {
			break
		}
		summary += fmt.Sprintf("• Topic %d: Discussion about \"%s\"\n", i+1, topic)
	}

	summary += fmt.Sprintf("• Most recent activity focused on the latest messages.\n")

	return summary, topics
}

// GenerateSmartReplies produces mock context-aware reply suggestions.
func GenerateSmartReplies(lastMessages []string, count int) []string {
	defaults := []string{
		"Got it, thanks! 👍",
		"Let me check and get back to you.",
		"Sounds good!",
		"I'll take a look.",
		"Can we discuss this later?",
	}

	if count <= 0 || count > len(defaults) {
		count = 3
	}
	return defaults[:count]
}

// TranslateText performs mock translation.
func TranslateText(text, sourceLang, targetLang string) (string, string) {
	detected := sourceLang
	if detected == "" {
		detected = "en"
	}

	// Mock: prefix the text with target language indicator
	translated := fmt.Sprintf("[%s] %s", strings.ToUpper(targetLang), text)
	return translated, detected
}

// AdjustTextTone rewrites text with a different tone (mock).
func AdjustTextTone(text, tone string) (string, string) {
	originalTone := "neutral"

	switch tone {
	case "formal":
		return fmt.Sprintf("Dear colleague, %s. Kind regards.", text), originalTone
	case "casual":
		return fmt.Sprintf("Hey! %s 😄", text), originalTone
	case "friendly":
		return fmt.Sprintf("Hi there! %s Hope that helps! 🙌", text), originalTone
	case "professional":
		return fmt.Sprintf("Please note: %s. Please advise.", text), originalTone
	case "humorous":
		return fmt.Sprintf("Plot twist: %s 😂", text), originalTone
	default:
		return text, originalTone
	}
}

// ExtractActions extracts mock action items from messages.
func ExtractActions(messages []string) ([]ActionItem, []string) {
	var items []ActionItem
	var meetings []string

	for _, msg := range messages {
		lower := strings.ToLower(msg)

		if strings.Contains(lower, "todo") || strings.Contains(lower, "task") || strings.Contains(lower, "need to") {
			items = append(items, ActionItem{
				Description: msg,
				Priority:    "medium",
			})
		}

		if strings.Contains(lower, "meeting") || strings.Contains(lower, "call") || strings.Contains(lower, "schedule") {
			meetings = append(meetings, msg)
		}
	}

	if len(items) == 0 {
		items = append(items, ActionItem{
			Description: "No explicit action items found in the conversation.",
			Priority:    "low",
		})
	}

	return items, meetings
}

// ActionItem represents an extracted action.
type ActionItem struct {
	Description string
	Assignee    string
	DueDate     string
	Priority    string
}

// extractTopics does basic keyword frequency analysis to find topics.
func extractTopics(messages []string) []string {
	wordCount := make(map[string]int)
	stopWords := map[string]bool{
		"the": true, "a": true, "is": true, "it": true, "to": true,
		"and": true, "of": true, "in": true, "for": true, "on": true,
		"i": true, "you": true, "we": true, "he": true, "she": true,
		"that": true, "this": true, "with": true, "are": true, "was": true,
		"be": true, "have": true, "has": true, "had": true, "do": true,
		"did": true, "but": true, "or": true, "an": true, "not": true,
		"my": true, "me": true, "so": true, "if": true, "at": true,
	}

	for _, msg := range messages {
		words := strings.Fields(strings.ToLower(msg))
		for _, w := range words {
			w = strings.Trim(w, ".,!?;:'\"")
			if len(w) > 2 && !stopWords[w] {
				wordCount[w]++
			}
		}
	}

	// Get top words as topics
	type wc struct {
		word  string
		count int
	}
	var sorted []wc
	for w, c := range wordCount {
		sorted = append(sorted, wc{w, c})
	}

	// Simple sort by frequency
	for i := 0; i < len(sorted); i++ {
		for j := i + 1; j < len(sorted); j++ {
			if sorted[j].count > sorted[i].count {
				sorted[i], sorted[j] = sorted[j], sorted[i]
			}
		}
	}

	var topics []string
	for i, wc := range sorted {
		if i >= 5 {
			break
		}
		topics = append(topics, wc.word)
	}

	return topics
}
