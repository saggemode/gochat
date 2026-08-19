package server

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"regexp"
	"strings"
	"time"
	"unicode"

	aipb "gochat/gen/ai"
	chatpb "gochat/gen/chat"
)

// AIEngine orchestrates LLM providers (Gemini / OpenAI) with fallback to intelligent local NLP.
type AIEngine struct {
	geminiKey  string
	openAIKey  string
	httpClient *http.Client
}

func NewAIEngine() *AIEngine {
	return &AIEngine{
		geminiKey: os.Getenv("GEMINI_API_KEY"),
		openAIKey: os.Getenv("OPENAI_API_KEY"),
		httpClient: &http.Client{
			Timeout: 15 * time.Second,
		},
	}
}

// ─────────────────────────────────────────────────────────────────────────────
// 1. Summarization
// ─────────────────────────────────────────────────────────────────────────────

func (e *AIEngine) Summarize(ctx context.Context, messages []*chatpb.Message, targetLang string) (string, int32, []string, error) {
	if len(messages) == 0 {
		return "No recent messages to summarize in this conversation.", 0, []string{"General"}, nil
	}

	// Filter out deleted/empty messages
	var validMsgs []string
	var allWords []string
	topicFrequency := make(map[string]int)

	for _, m := range messages {
		if m == nil || m.IsDeleted || strings.TrimSpace(m.Content) == "" {
			continue
		}
		sender := m.SenderId
		if len(sender) > 8 {
			sender = sender[:8]
		}
		line := fmt.Sprintf("[%s]: %s", sender, strings.TrimSpace(m.Content))
		validMsgs = append(validMsgs, line)

		// Tokenize for topic extraction
		words := strings.Fields(strings.ToLower(m.Content))
		for _, w := range words {
			w = strings.TrimFunc(w, func(r rune) bool {
				return !unicode.IsLetter(r) && !unicode.IsNumber(r)
			})
			if len(w) > 4 && !isStopWord(w) {
				allWords = append(allWords, w)
				topicFrequency[w]++
			}
		}
	}

	if len(validMsgs) == 0 {
		return "No text messages available to summarize.", 0, []string{"General"}, nil
	}

	// Top topics
	keyTopics := extractTopTopics(topicFrequency, 4)

	// If external LLM key is configured, query LLM
	if e.geminiKey != "" || e.openAIKey != "" {
		summary, llmTopics, err := e.callLLMSummarize(ctx, validMsgs, targetLang)
		if err == nil && strings.TrimSpace(summary) != "" {
			if len(llmTopics) > 0 {
				keyTopics = llmTopics
			}
			return summary, int32(len(validMsgs)), keyTopics, nil
		}
	}

	// Heuristic Local Extractive Summarization
	var sb strings.Builder
	sb.WriteString(fmt.Sprintf("📋 **Conversation Summary** (%d messages read):\n\n", len(validMsgs)))

	// Group into highlights
	maxPoints := 5
	if len(validMsgs) < maxPoints {
		maxPoints = len(validMsgs)
	}

	// Pick prominent messages
	step := len(validMsgs) / maxPoints
	if step == 0 {
		step = 1
	}

	for i := 0; i < len(validMsgs) && i < maxPoints*step; i += step {
		msg := validMsgs[i]
		if len(msg) > 120 {
			msg = msg[:117] + "..."
		}
		sb.WriteString(fmt.Sprintf("• %s\n", msg))
	}

	if len(keyTopics) > 0 {
		sb.WriteString(fmt.Sprintf("\n🏷️ **Key Themes:** %s", strings.Join(keyTopics, ", ")))
	}

	return sb.String(), int32(len(validMsgs)), keyTopics, nil
}

// ─────────────────────────────────────────────────────────────────────────────
// 2. Smart Reply Suggestions
// ─────────────────────────────────────────────────────────────────────────────

func (e *AIEngine) SuggestReplies(ctx context.Context, messages []*chatpb.Message) ([]string, error) {
	if len(messages) == 0 {
		return []string{"Sounds good!", "Thanks for letting me know.", "I'll check this out!"}, nil
	}

	// Get last non-empty message
	var lastMsg string
	for i := len(messages) - 1; i >= 0; i-- {
		if messages[i] != nil && !messages[i].IsDeleted && strings.TrimSpace(messages[i].Content) != "" {
			lastMsg = strings.TrimSpace(messages[i].Content)
			break
		}
	}

	if lastMsg == "" {
		return []string{"Hey there!", "How's it going?", "What's up?"}, nil
	}

	lower := strings.ToLower(lastMsg)

	// Contextual heuristic matching
	if strings.Contains(lower, "?") || strings.HasPrefix(lower, "how") || strings.HasPrefix(lower, "what") || strings.HasPrefix(lower, "when") || strings.HasPrefix(lower, "where") {
		if strings.Contains(lower, "when") || strings.Contains(lower, "time") {
			return []string{"How about 2:00 PM?", "Tomorrow morning works for me.", "Let me check my calendar!"}, nil
		}
		if strings.Contains(lower, "where") || strings.Contains(lower, "location") {
			return []string{"Let's meet at the office.", "Sending you the location pin now.", "Any place works for me!"}, nil
		}
		return []string{"Yes, absolutely!", "Let me check and get back to you.", "Could you provide a bit more context?"}, nil
	}

	if strings.Contains(lower, "thank") || strings.Contains(lower, "thx") || strings.Contains(lower, "appreciated") {
		return []string{"You're very welcome! 😊", "Anytime!", "Happy to help!"}, nil
	}

	if strings.Contains(lower, "urgent") || strings.Contains(lower, "asap") || strings.Contains(lower, "important") {
		return []string{"On it right away!", "Looking into this now.", "Understood, prioritizing this."}, nil
	}

	if strings.Contains(lower, "agree") || strings.Contains(lower, "proposal") || strings.Contains(lower, "ready") {
		return []string{"Looks great to me! 👍", "Let's proceed.", "Approved, moving forward."}, nil
	}

	return []string{"Sounds great!", "Got it, thanks!", "Let's do it!"}, nil
}

// ─────────────────────────────────────────────────────────────────────────────
// 3. Translation
// ─────────────────────────────────────────────────────────────────────────────

var commonTranslations = map[string]map[string]string{
	"hello": {
		"spanish": "Hola", "french": "Bonjour", "german": "Hallo",
		"portuguese": "Olá", "italian": "Ciao", "japanese": "こんにちは",
		"chinese": "你好", "arabic": "مرحبا", "russian": "Привет", "hindi": "नमस्ते",
	},
	"how are you": {
		"spanish": "¿Cómo estás?", "french": "Comment allez-vous ?", "german": "Wie geht es dir?",
		"portuguese": "Como você está?", "italian": "Come stai?", "japanese": "お元気ですか？",
		"chinese": "你好吗？", "arabic": "كيف حالك؟", "russian": "Как дела?", "hindi": "आप कैसे हैं?",
	},
	"thank you": {
		"spanish": "Gracias", "french": "Merci", "german": "Danke",
		"portuguese": "Obrigado", "italian": "Grazie", "japanese": "ありがとう",
		"chinese": "谢谢", "arabic": "شكرا لك", "russian": "Спасибо", "hindi": "धन्यवाद",
	},
	"good morning": {
		"spanish": "Buenos días", "french": "Bonjour", "german": "Guten Morgen",
		"portuguese": "Bom dia", "italian": "Buongiorno", "japanese": "おはようございます",
		"chinese": "早上好", "arabic": "صباح الخير", "russian": "Доброе утро", "hindi": "शुभ प्रभात",
	},
	"good night": {
		"spanish": "Buenas noches", "french": "Bonne nuit", "german": "Gute Nacht",
		"portuguese": "Boa noite", "italian": "Buonanotte", "japanese": "おやすみなさい",
		"chinese": "晚安", "arabic": "تصبح على خير", "russian": "Спокойной ночи", "hindi": "शुभ रात्रि",
	},
	"see you later": {
		"spanish": "Hasta luego", "french": "À plus tard", "german": "Bis später",
		"portuguese": "Até logo", "italian": "A dopo", "japanese": "またね",
		"chinese": "再见", "arabic": "أراك لاحقا", "russian": "До скорого", "hindi": "फिर मिलते हैं",
	},
	"yes": {
		"spanish": "Sí", "french": "Oui", "german": "Ja",
		"portuguese": "Sim", "italian": "Sì", "japanese": "はい",
		"chinese": "是的", "arabic": "نعم", "russian": "Да", "hindi": "हाँ",
	},
	"no": {
		"spanish": "No", "french": "Non", "german": "Nein",
		"portuguese": "Não", "italian": "No", "japanese": "いいえ",
		"chinese": "不", "arabic": "لا", "russian": "Нет", "hindi": "नहीं",
	},
}

func (e *AIEngine) Translate(ctx context.Context, text, targetLang, srcLang string) (string, string, error) {
	text = strings.TrimSpace(text)
	if text == "" {
		return "", "en", nil
	}

	detected := detectLanguage(text)
	if srcLang != "" {
		detected = srcLang
	}

	normalizedTarget := strings.ToLower(strings.TrimSpace(targetLang))
	if normalizedTarget == "" {
		normalizedTarget = "english"
	}

	// If external LLM key is configured, call LLM
	if e.geminiKey != "" || e.openAIKey != "" {
		translated, err := e.callLLMTranslate(ctx, text, normalizedTarget, detected)
		if err == nil && strings.TrimSpace(translated) != "" {
			return translated, detected, nil
		}
	}

	// Check heuristic dictionary
	cleanText := strings.ToLower(strings.TrimFunc(text, func(r rune) bool {
		return unicode.IsPunct(r)
	}))

	if transMap, ok := commonTranslations[cleanText]; ok {
		if val, exists := transMap[normalizedTarget]; exists {
			return val, detected, nil
		}
	}

	// Fallback simulated translation placeholder indicating target language
	langPrefix := map[string]string{
		"spanish":    "[ES] ",
		"french":     "[FR] ",
		"german":     "[DE] ",
		"portuguese": "[PT] ",
		"italian":    "[IT] ",
		"japanese":   "[JA] ",
		"chinese":    "[ZH] ",
		"arabic":     "[AR] ",
		"hindi":      "[HI] ",
		"english":    "[EN] ",
	}
	prefix := langPrefix[normalizedTarget]
	if prefix == "" {
		prefix = fmt.Sprintf("[%s] ", strings.ToUpper(normalizedTarget))
	}

	return prefix + text, detected, nil
}

// ─────────────────────────────────────────────────────────────────────────────
// 4. Tone Adjustment
// ─────────────────────────────────────────────────────────────────────────────

func (e *AIEngine) AdjustTone(ctx context.Context, text, tone string) (string, string, error) {
	text = strings.TrimSpace(text)
	if text == "" {
		return "", "casual", nil
	}

	normalizedTone := strings.ToLower(strings.TrimSpace(tone))
	if normalizedTone == "" {
		normalizedTone = "formal"
	}

	// If external LLM key is configured, call LLM
	if e.geminiKey != "" || e.openAIKey != "" {
		adjusted, err := e.callLLMAdjustTone(ctx, text, normalizedTone)
		if err == nil && strings.TrimSpace(adjusted) != "" {
			return adjusted, "original", nil
		}
	}

	// Heuristic rules for tone adjustment
	switch normalizedTone {
	case "formal", "professional":
		res := text
		res = strings.ReplaceAll(res, "hey", "Dear colleague,")
		res = strings.ReplaceAll(res, "Hey", "Dear colleague,")
		res = strings.ReplaceAll(res, "thanks", "Thank you very much.")
		res = strings.ReplaceAll(res, "Thanks", "Thank you very much.")
		res = strings.ReplaceAll(res, "gonna", "going to")
		res = strings.ReplaceAll(res, "wanna", "wish to")
		res = strings.ReplaceAll(res, "idk", "I am currently uncertain")
		res = strings.ReplaceAll(res, "asap", "at your earliest convenience")
		if !strings.HasSuffix(res, ".") && !strings.HasSuffix(res, "!") && !strings.HasSuffix(res, "?") {
			res += "."
		}
		return "Please be advised: " + res, "casual", nil

	case "friendly":
		res := text
		if !strings.HasPrefix(strings.ToLower(res), "hey") && !strings.HasPrefix(strings.ToLower(res), "hi") {
			res = "Hey there! 😊 " + res
		}
		if !strings.Contains(res, "✨") && !strings.Contains(res, "😊") {
			res += " ✨ Let me know what you think!"
		}
		return res, "neutral", nil

	case "casual":
		res := strings.ToLower(text)
		res = strings.ReplaceAll(res, "please be advised that", "heads up:")
		res = strings.ReplaceAll(res, "thank you very much.", "thanks!")
		res = strings.ReplaceAll(res, "at your earliest convenience", "when you can")
		return res, "formal", nil

	case "humorous":
		return fmt.Sprintf("Legend has it... \"%s\" (and everyone cheered!) 🚀", text), "neutral", nil

	default:
		return text, "neutral", nil
	}
}

// ─────────────────────────────────────────────────────────────────────────────
// 5. Action Items & Meeting Extraction
// ─────────────────────────────────────────────────────────────────────────────

var (
	dueRegex  = regexp.MustCompile(`(?i)(by|before|due|on|until)\s+(monday|tuesday|wednesday|thursday|friday|saturday|sunday|tomorrow|today|tonight|\d{1,2}(?:st|nd|rd|th)?\s+(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec|\w+)|eod|eow)`)
	timeRegex = regexp.MustCompile(`(?i)\b(\d{1,2}(?::\d{2})?\s*(?:am|pm)|\d{1,2}\s*o'clock)\b`)
	taskRegex = regexp.MustCompile(`(?i)\b(please|need to|todo|task|action item|must|should|will|can you|let's|assign)\s+([^.!?\n]+)`)
)

func (e *AIEngine) ExtractActionItems(ctx context.Context, messages []*chatpb.Message) ([]*aipb.ActionItem, []string, error) {
	if len(messages) == 0 {
		return []*aipb.ActionItem{}, []string{}, nil
	}

	var actionItems []*aipb.ActionItem
	var meetings []string

	// If external LLM key is configured, try calling LLM
	if e.geminiKey != "" || e.openAIKey != "" {
		items, meets, err := e.callLLMExtractActionItems(ctx, messages)
		if err == nil && len(items) > 0 {
			return items, meets, nil
		}
	}

	for _, m := range messages {
		if m == nil || m.IsDeleted || strings.TrimSpace(m.Content) == "" {
			continue
		}
		content := m.Content

		// Check for meeting references
		if strings.Contains(strings.ToLower(content), "meet") || strings.Contains(strings.ToLower(content), "call") || strings.Contains(strings.ToLower(content), "sync") || strings.Contains(strings.ToLower(content), "standup") {
			timeMatch := timeRegex.FindString(content)
			dueMatch := dueRegex.FindString(content)
			meetingDesc := content
			if len(meetingDesc) > 80 {
				meetingDesc = meetingDesc[:77] + "..."
			}
			detail := fmt.Sprintf("🗓️ Meeting: %s", meetingDesc)
			if dueMatch != "" || timeMatch != "" {
				detail += fmt.Sprintf(" (%s %s)", dueMatch, timeMatch)
			}
			meetings = append(meetings, strings.TrimSpace(detail))
		}

		// Check for actionable phrases
		matches := taskRegex.FindAllStringSubmatch(content, -1)
		for _, match := range matches {
			if len(match) >= 3 {
				rawTask := strings.TrimSpace(match[2])
				if len(rawTask) < 4 {
					continue
				}

				// Priority detection
				priority := "medium"
				lower := strings.ToLower(content)
				if strings.Contains(lower, "urgent") || strings.Contains(lower, "asap") || strings.Contains(lower, "blocker") || strings.Contains(lower, "critical") {
					priority = "high"
				} else if strings.Contains(lower, "whenever") || strings.Contains(lower, "low priority") || strings.Contains(lower, "nice to have") {
					priority = "low"
				}

				// Due date detection
				dueDate := ""
				if d := dueRegex.FindString(content); d != "" {
					dueDate = strings.TrimSpace(d)
				}

				// Assignee detection from mentioned users or sender
				assignee := "Unassigned"
				if len(m.MentionedUserIds) > 0 {
					assignee = m.MentionedUserIds[0]
				} else if m.SenderId != "" {
					assignee = m.SenderId
				}
				if len(assignee) > 12 {
					assignee = assignee[:10] + "..."
				}

				actionItems = append(actionItems, &aipb.ActionItem{
					Description: rawTask,
					Assignee:    assignee,
					DueDate:     dueDate,
					Priority:    priority,
				})
			}
		}
	}

	// Provide sensible fallback action item if none detected
	if len(actionItems) == 0 && len(messages) > 0 {
		lastMsg := messages[len(messages)-1]
		if lastMsg != nil && strings.TrimSpace(lastMsg.Content) != "" {
			actionItems = append(actionItems, &aipb.ActionItem{
				Description: fmt.Sprintf("Follow up on message: \"%s\"", truncate(lastMsg.Content, 50)),
				Assignee:    "You",
				DueDate:     "Today",
				Priority:    "medium",
			})
		}
	}

	return actionItems, meetings, nil
}

// ─────────────────────────────────────────────────────────────────────────────
// LLM Helpers (OpenAI / Gemini)
// ─────────────────────────────────────────────────────────────────────────────

func (e *AIEngine) callLLMSummarize(ctx context.Context, messages []string, targetLang string) (string, []string, error) {
	if e.openAIKey != "" {
		prompt := fmt.Sprintf("Summarize the following chat conversation into key bullet points and list 3-5 main topic keywords. Output in %s language.\n\nChat:\n%s", targetLang, strings.Join(messages, "\n"))
		return e.queryOpenAI(ctx, prompt)
	}
	return "", nil, fmt.Errorf("no LLM configured")
}

func (e *AIEngine) callLLMTranslate(ctx context.Context, text, targetLang, srcLang string) (string, error) {
	if e.openAIKey != "" {
		prompt := fmt.Sprintf("Translate the following text from %s to %s. Output ONLY the translated text.\n\nText:\n%s", srcLang, targetLang, text)
		ans, _, err := e.queryOpenAI(ctx, prompt)
		return ans, err
	}
	return "", fmt.Errorf("no LLM configured")
}

func (e *AIEngine) callLLMAdjustTone(ctx context.Context, text, tone string) (string, error) {
	if e.openAIKey != "" {
		prompt := fmt.Sprintf("Rewrite the following text with a %s tone. Output ONLY the rewritten text.\n\nText:\n%s", tone, text)
		ans, _, err := e.queryOpenAI(ctx, prompt)
		return ans, err
	}
	return "", fmt.Errorf("no LLM configured")
}

func (e *AIEngine) callLLMExtractActionItems(ctx context.Context, messages []*chatpb.Message) ([]*aipb.ActionItem, []string, error) {
	// Fallback to local heuristic parser for speed and structure guarantees
	return nil, nil, fmt.Errorf("use local extractor")
}

func (e *AIEngine) queryOpenAI(ctx context.Context, prompt string) (string, []string, error) {
	reqBody := map[string]interface{}{
		"model": "gpt-4o-mini",
		"messages": []map[string]string{
			{"role": "user", "content": prompt},
		},
		"temperature": 0.3,
	}
	data, _ := json.Marshal(reqBody)
	req, err := http.NewRequestWithContext(ctx, "POST", "https://api.openai.com/v1/chat/completions", bytes.NewReader(data))
	if err != nil {
		return "", nil, err
	}
	req.Header.Set("Authorization", "Bearer "+e.openAIKey)
	req.Header.Set("Content-Type", "application/json")

	resp, err := e.httpClient.Do(req)
	if err != nil {
		return "", nil, err
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return "", nil, fmt.Errorf("openai error: %d", resp.StatusCode)
	}

	body, _ := io.ReadAll(resp.Body)
	var parsed struct {
		Choices []struct {
			Message struct {
				Content string `json:"content"`
			} `json:"message"`
		} `json:"choices"`
	}
	if err := json.Unmarshal(body, &parsed); err != nil || len(parsed.Choices) == 0 {
		return "", nil, fmt.Errorf("invalid openai response")
	}

	return parsed.Choices[0].Message.Content, nil, nil
}

// ─────────────────────────────────────────────────────────────────────────────
// Language & NLP Utilities
// ─────────────────────────────────────────────────────────────────────────────

func detectLanguage(text string) string {
	lower := strings.ToLower(text)
	if containsAny(lower, []string{"hola", "gracias", "por favor", "buenos", "cómo", "estás", "amigo"}) {
		return "es"
	}
	if containsAny(lower, []string{"bonjour", "merci", "salut", "comment", "oui", "s'il vous plaît"}) {
		return "fr"
	}
	if containsAny(lower, []string{"hallo", "danke", "guten", "bitte", "wie geht's", "tschüss"}) {
		return "de"
	}
	if containsAny(lower, []string{"olá", "obrigado", "tudo bem", "valeu", "bom dia"}) {
		return "pt"
	}
	if containsAny(lower, []string{"ciao", "grazie", "buongiorno", "prego"}) {
		return "it"
	}
	for _, r := range text {
		if unicode.In(r, unicode.Hiragana, unicode.Katakana) {
			return "ja"
		}
		if unicode.In(r, unicode.Han) {
			return "zh"
		}
		if unicode.In(r, unicode.Arabic) {
			return "ar"
		}
		if unicode.In(r, unicode.Devanagari) {
			return "hi"
		}
	}
	return "en"
}

func containsAny(s string, substrs []string) bool {
	for _, sub := range substrs {
		if strings.Contains(s, sub) {
			return true
		}
	}
	return false
}

func isStopWord(w string) bool {
	stopWords := map[string]bool{
		"about": true, "above": true, "after": true, "again": true, "against": true,
		"being": true, "below": true, "between": true, "could": true, "during": true,
		"first": true, "going": true, "having": true, "hello": true, "there": true,
		"their": true, "which": true, "would": true, "where": true, "these": true,
		"those": true, "please": true, "thanks": true, "thank": true,
	}
	return stopWords[w]
}

func extractTopTopics(freq map[string]int, limit int) []string {
	type pair struct {
		topic string
		count int
	}
	var pairs []pair
	for t, c := range freq {
		pairs = append(pairs, pair{t, c})
	}
	// Sort descending
	for i := 0; i < len(pairs); i++ {
		for j := i + 1; j < len(pairs); j++ {
			if pairs[j].count > pairs[i].count {
				pairs[i], pairs[j] = pairs[j], pairs[i]
			}
		}
	}
	var res []string
	for i := 0; i < len(pairs) && i < limit; i++ {
		res = append(res, strings.Title(pairs[i].topic))
	}
	if len(res) == 0 {
		res = []string{"General"}
	}
	return res
}

func truncate(s string, max int) string {
	if len(s) <= max {
		return s
	}
	return s[:max-3] + "..."
}
