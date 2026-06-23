package server

import (
	"context"

	"github.com/jackc/pgx/v5/pgxpool"
	"fmt"
	"strings"

	aipb "gochat/gen/ai"
	chatpb "gochat/gen/chat"
	"gochat/services/ai/repository"

	"go.uber.org/zap"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
)

// AIServer implements the AIService gRPC server.
type AIServer struct {
	aipb.UnimplementedAIServiceServer
	repo       *repository.AIRepository
	chatClient chatpb.ChatServiceClient
	log        *zap.Logger
}

// NewAIServer constructs the server with its dependencies.
func NewAIServer(db *pgxpool.Pool, log *zap.Logger) *AIServer {
	return &AIServer{
		repo: repository.NewAIRepository(db),
		log:  log,
	}
}

// SummarizeChat fetches recent messages and generates a summary.
func (s *AIServer) SummarizeChat(ctx context.Context, req *aipb.SummarizeChatRequest) (*aipb.SummarizeChatResponse, error) {
	if req.UserId == "" || req.ConversationId == "" {
		return nil, status.Error(codes.InvalidArgument, "user_id and conversation_id required")
	}

	limit := int32(100)
	if req.MessageCount > 0 {
		limit = req.MessageCount
	}

	lang := "en"
	if req.Language != "" {
		lang = req.Language
	}

	// Fetch recent messages from chat service
	resp, err := s.chatClient.GetMessages(ctx, &chatpb.GetMessagesRequest{
		ConversationId: req.ConversationId,
		UserId:         req.UserId,
		Limit:          limit,
	})
	if err != nil {
		return nil, status.Errorf(codes.Internal, "failed to fetch messages: %v", err)
	}

	var texts []string
	for _, m := range resp.Messages {
		if m.Content != "" && !m.IsDeleted {
			texts = append(texts, m.Content)
		}
	}

	summary, topics := repository.GenerateSummary(texts, lang)

	// Cache the summary
	_, cacheErr := s.repo.SaveSummary(ctx, req.ConversationId, req.UserId, summary, lang, len(texts))
	if cacheErr != nil {
		s.log.Warn("failed to cache summary", zap.Error(cacheErr))
	}

	return &aipb.SummarizeChatResponse{
		Summary:      summary,
		MessagesRead: int32(len(texts)),
		KeyTopics:    topics,
	}, nil
}

// SuggestReplies generates context-aware smart reply suggestions.
func (s *AIServer) SuggestReplies(ctx context.Context, req *aipb.SuggestRepliesRequest) (*aipb.SuggestRepliesResponse, error) {
	if req.UserId == "" || req.ConversationId == "" {
		return nil, status.Error(codes.InvalidArgument, "user_id and conversation_id required")
	}

	// Check cache first
	cached, err := s.repo.GetCachedReplies(ctx, req.ConversationId, req.UserId)
	if err == nil && len(cached) > 0 {
		return &aipb.SuggestRepliesResponse{Suggestions: cached}, nil
	}

	// Fetch last few messages for context
	resp, err := s.chatClient.GetMessages(ctx, &chatpb.GetMessagesRequest{
		ConversationId: req.ConversationId,
		UserId:         req.UserId,
		Limit:          10,
	})
	if err != nil {
		return nil, status.Errorf(codes.Internal, "failed to fetch messages: %v", err)
	}

	var texts []string
	for _, m := range resp.Messages {
		if m.Content != "" {
			texts = append(texts, m.Content)
		}
	}

	count := int(req.Count)
	if count <= 0 {
		count = 3
	}

	suggestions := repository.GenerateSmartReplies(texts, count)

	// Cache suggestions
	_ = s.repo.SaveSmartReplies(ctx, req.ConversationId, req.UserId, suggestions)

	return &aipb.SuggestRepliesResponse{Suggestions: suggestions}, nil
}

// TranslateMessage translates text to the target language.
func (s *AIServer) TranslateMessage(ctx context.Context, req *aipb.TranslateMessageRequest) (*aipb.TranslateMessageResponse, error) {
	if req.Text == "" || req.TargetLanguage == "" {
		return nil, status.Error(codes.InvalidArgument, "text and target_language required")
	}

	translated, detected := repository.TranslateText(req.Text, req.SourceLanguage, req.TargetLanguage)

	return &aipb.TranslateMessageResponse{
		TranslatedText:   translated,
		DetectedLanguage: detected,
	}, nil
}

// AdjustTone rewrites text with a different tone.
func (s *AIServer) AdjustTone(ctx context.Context, req *aipb.AdjustToneRequest) (*aipb.AdjustToneResponse, error) {
	if req.Text == "" || req.Tone == "" {
		return nil, status.Error(codes.InvalidArgument, "text and tone required")
	}

	validTones := map[string]bool{
		"formal": true, "casual": true, "friendly": true,
		"professional": true, "humorous": true,
	}
	if !validTones[strings.ToLower(req.Tone)] {
		return nil, status.Errorf(codes.InvalidArgument,
			"invalid tone: %s. Valid: formal, casual, friendly, professional, humorous", req.Tone)
	}

	adjusted, original := repository.AdjustTextTone(req.Text, strings.ToLower(req.Tone))

	return &aipb.AdjustToneResponse{
		AdjustedText: adjusted,
		OriginalTone: original,
	}, nil
}

// ExtractActionItems finds tasks and meetings in conversation messages.
func (s *AIServer) ExtractActionItems(ctx context.Context, req *aipb.ExtractActionItemsRequest) (*aipb.ExtractActionItemsResponse, error) {
	if req.UserId == "" || req.ConversationId == "" {
		return nil, status.Error(codes.InvalidArgument, "user_id and conversation_id required")
	}

	limit := int32(50)
	if req.MessageCount > 0 {
		limit = req.MessageCount
	}

	resp, err := s.chatClient.GetMessages(ctx, &chatpb.GetMessagesRequest{
		ConversationId: req.ConversationId,
		UserId:         req.UserId,
		Limit:          limit,
	})
	if err != nil {
		return nil, status.Errorf(codes.Internal, "failed to fetch messages: %v", err)
	}

	var texts []string
	for _, m := range resp.Messages {
		if m.Content != "" && !m.IsDeleted {
			texts = append(texts, fmt.Sprintf("[%s]: %s", m.SenderId, m.Content))
		}
	}

	items, meetings := repository.ExtractActions(texts)

	var pbItems []*aipb.ActionItem
	for _, item := range items {
		pbItems = append(pbItems, &aipb.ActionItem{
			Description: item.Description,
			Assignee:    item.Assignee,
			DueDate:     item.DueDate,
			Priority:    item.Priority,
		})
	}

	return &aipb.ExtractActionItemsResponse{
		ActionItems: pbItems,
		Meetings:    meetings,
	}, nil
}
