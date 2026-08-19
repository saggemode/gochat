package server

import (
	"context"
	"strings"

	"go.uber.org/zap"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"

	aipb "gochat/gen/ai"
	chatpb "gochat/gen/chat"
)

// AIServer implements aipb.AIServiceServer.
type AIServer struct {
	aipb.UnimplementedAIServiceServer
	chatClient chatpb.ChatServiceClient
	engine     *AIEngine
	log        *zap.Logger
}

// NewAIServer constructs the AI gRPC server.
func NewAIServer(chatClient chatpb.ChatServiceClient, log *zap.Logger) *AIServer {
	return &AIServer{
		chatClient: chatClient,
		engine:     NewAIEngine(),
		log:        log,
	}
}

// SummarizeChat summarizes the recent messages in a conversation.
func (s *AIServer) SummarizeChat(ctx context.Context, req *aipb.SummarizeChatRequest) (*aipb.SummarizeChatResponse, error) {
	if req.ConversationId == "" {
		return nil, status.Error(codes.InvalidArgument, "conversation_id is required")
	}

	limit := req.MessageCount
	if limit <= 0 || limit > 500 {
		limit = 100
	}

	targetLang := req.Language
	if strings.TrimSpace(targetLang) == "" {
		targetLang = "en"
	}

	var messages []*chatpb.Message
	if s.chatClient != nil {
		msgResp, err := s.chatClient.GetMessages(ctx, &chatpb.GetMessagesRequest{
			ConversationId: req.ConversationId,
			UserId:         req.UserId,
			Limit:          limit,
		})
		if err == nil && msgResp != nil {
			// GetMessages returns newest first, so we reverse for chronological order
			messages = make([]*chatpb.Message, len(msgResp.Messages))
			for i, m := range msgResp.Messages {
				messages[len(msgResp.Messages)-1-i] = m
			}
		} else {
			s.log.Warn("could not fetch messages from chat service, summarizing with empty set", zap.Error(err))
		}
	}

	summary, readCount, topics, err := s.engine.Summarize(ctx, messages, targetLang)
	if err != nil {
		s.log.Error("summarization failed", zap.Error(err))
		return nil, status.Errorf(codes.Internal, "failed to summarize chat: %v", err)
	}

	return &aipb.SummarizeChatResponse{
		Summary:      summary,
		MessagesRead: readCount,
		KeyTopics:    topics,
	}, nil
}

// SuggestReplies returns context-aware quick response chips.
func (s *AIServer) SuggestReplies(ctx context.Context, req *aipb.SuggestRepliesRequest) (*aipb.SuggestRepliesResponse, error) {
	if req.ConversationId == "" {
		return nil, status.Error(codes.InvalidArgument, "conversation_id is required")
	}

	var messages []*chatpb.Message
	if s.chatClient != nil {
		msgResp, err := s.chatClient.GetMessages(ctx, &chatpb.GetMessagesRequest{
			ConversationId: req.ConversationId,
			UserId:         req.UserId,
			Limit:          10,
		})
		if err == nil && msgResp != nil {
			messages = msgResp.Messages
		}
	}

	replies, err := s.engine.SuggestReplies(ctx, messages)
	if err != nil {
		s.log.Error("suggest replies failed", zap.Error(err))
		return nil, status.Errorf(codes.Internal, "failed to suggest replies: %v", err)
	}

	maxCount := int(req.Count)
	if maxCount > 0 && len(replies) > maxCount {
		replies = replies[:maxCount]
	}

	return &aipb.SuggestRepliesResponse{
		Suggestions: replies,
	}, nil
}

// TranslateMessage translates text into a target language.
func (s *AIServer) TranslateMessage(ctx context.Context, req *aipb.TranslateMessageRequest) (*aipb.TranslateMessageResponse, error) {
	if strings.TrimSpace(req.Text) == "" {
		return nil, status.Error(codes.InvalidArgument, "text is required")
	}
	if strings.TrimSpace(req.TargetLanguage) == "" {
		return nil, status.Error(codes.InvalidArgument, "target_language is required")
	}

	translated, detected, err := s.engine.Translate(ctx, req.Text, req.TargetLanguage, req.SourceLanguage)
	if err != nil {
		s.log.Error("translation failed", zap.Error(err))
		return nil, status.Errorf(codes.Internal, "failed to translate message: %v", err)
	}

	return &aipb.TranslateMessageResponse{
		TranslatedText:   translated,
		DetectedLanguage: detected,
	}, nil
}

// AdjustTone transforms draft text into the requested style.
func (s *AIServer) AdjustTone(ctx context.Context, req *aipb.AdjustToneRequest) (*aipb.AdjustToneResponse, error) {
	if strings.TrimSpace(req.Text) == "" {
		return nil, status.Error(codes.InvalidArgument, "text is required")
	}
	if strings.TrimSpace(req.Tone) == "" {
		return nil, status.Error(codes.InvalidArgument, "tone is required")
	}

	adjusted, originalTone, err := s.engine.AdjustTone(ctx, req.Text, req.Tone)
	if err != nil {
		s.log.Error("adjust tone failed", zap.Error(err))
		return nil, status.Errorf(codes.Internal, "failed to adjust tone: %v", err)
	}

	return &aipb.AdjustToneResponse{
		AdjustedText: adjusted,
		OriginalTone: originalTone,
	}, nil
}

// ExtractActionItems extracts actionable items and meetings from a conversation.
func (s *AIServer) ExtractActionItems(ctx context.Context, req *aipb.ExtractActionItemsRequest) (*aipb.ExtractActionItemsResponse, error) {
	if req.ConversationId == "" {
		return nil, status.Error(codes.InvalidArgument, "conversation_id is required")
	}

	limit := req.MessageCount
	if limit <= 0 || limit > 200 {
		limit = 50
	}

	var messages []*chatpb.Message
	if s.chatClient != nil {
		msgResp, err := s.chatClient.GetMessages(ctx, &chatpb.GetMessagesRequest{
			ConversationId: req.ConversationId,
			UserId:         req.UserId,
			Limit:          limit,
		})
		if err == nil && msgResp != nil {
			messages = msgResp.Messages
		}
	}

	actionItems, meetings, err := s.engine.ExtractActionItems(ctx, messages)
	if err != nil {
		s.log.Error("extract action items failed", zap.Error(err))
		return nil, status.Errorf(codes.Internal, "failed to extract action items: %v", err)
	}

	return &aipb.ExtractActionItemsResponse{
		ActionItems: actionItems,
		Meetings:    meetings,
	}, nil
}
