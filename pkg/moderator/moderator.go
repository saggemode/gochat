package moderator

import (
	"context"
	"strings"

	grouppb "gochat/gen/group"
)

// ModerationResult represents the outcome of a bot moderation check.
type ModerationResult struct {
	Allowed bool
	Reason  string
	Action  string // "ignore", "warn", "delete", "mute", "kick", "ban"
}

// CheckMessage evaluates a message against a group's bot configuration and rules.
func CheckMessage(ctx context.Context, config *grouppb.BotConfig, content string) *ModerationResult {
	if config == nil || !config.IsActive {
		return &ModerationResult{Allowed: true}
	}

	// 1. Keyword Filter & Group Rules
	if hasPermission(config, grouppb.BotPermission_KEYWORD_FILTER) || hasPermission(config, grouppb.BotPermission_MANAGE_RULES) {
		rules := strings.Split(config.Rules, "\n")
		for _, rule := range rules {
			rule = strings.TrimSpace(rule)
			if rule == "" {
				continue
			}
			// Check if content contains prohibited phrase
			if strings.Contains(strings.ToLower(content), strings.ToLower(rule)) {
				return &ModerationResult{
					Allowed: false,
					Reason:  "message violates group rules / contains prohibited keywords",
					Action:  "delete",
				}
			}
		}
	}

	// 2. Anti-Spam (Basic Heuristics)
	if hasPermission(config, grouppb.BotPermission_ANTI_SPAM) {
		if config.SpamProtectionLevel >= 1 && isSpammy(content) {
			return &ModerationResult{
				Allowed: false,
				Reason:  "message flagged as spam by automated moderation",
				Action:  "delete",
			}
		}
	}

	// 3. Link Detection (if links are restricted)
	if hasPermission(config, grouppb.BotPermission_MANAGE_LINKS) {
		if (strings.Contains(content, "http://") || strings.Contains(content, "https://")) && !isWhitelistedLink(content) {
			return &ModerationResult{
				Allowed: false,
				Reason:  "links are not allowed in this group",
				Action:  "delete",
			}
		}
	}

	return &ModerationResult{Allowed: true}
}

func hasPermission(config *grouppb.BotConfig, perm grouppb.BotPermission) bool {
	for _, p := range config.Permissions {
		if p == perm {
			return true
		}
	}
	return false
}

func isSpammy(content string) bool {
	content = strings.ToLower(content)
	spamKeywords := []string{
		"buy now", "free money", "click here", "subscribe",
		"viagra", "crypto scam", "investment opportunity",
		"earn $", "work from home", "congratulations! you won",
	}
	for _, kw := range spamKeywords {
		if strings.Contains(content, kw) {
			return true
		}
	}

	// Check for excessive capitalization
	if len(content) > 10 {
		upperCount := 0
		for _, char := range content {
			if char >= 'A' && char <= 'Z' {
				upperCount++
			}
		}
		if float64(upperCount)/float64(len(content)) > 0.7 {
			return true
		}
	}

	return false
}

func isWhitelistedLink(content string) bool {
	// Example whitelisted domains
	whitelist := []string{"gochat.io", "google.com", "github.com"}
	for _, domain := range whitelist {
		if strings.Contains(content, domain) {
			return true
		}
	}
	return false
}
