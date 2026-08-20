package server

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"time"

	"github.com/google/uuid"
	"github.com/redis/go-redis/v9"
	"go.uber.org/zap"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"

	chatpb "gochat/gen/chat"
	"gochat/pkg/authz"
	"gochat/services/chat/repository"
)

// ChatServer implements the gRPC ChatService.
type ChatServer struct {
	chatpb.UnimplementedChatServiceServer

	convRepo      *repository.ConversationRepository
	msgRepo       *repository.MessageRepository
	folderRepo    *repository.FolderRepository
	labelRepo     *repository.LabelRepository
	analyticsRepo *repository.AnalyticsRepository
	notifRepo     *repository.NotificationProfileRepository
	pollRepo      *repository.PollRepository
	redis         *redis.Client
	authz         *authz.Client
	log           *zap.Logger
}

// New creates a ChatServer.
func New(
	convRepo *repository.ConversationRepository,
	msgRepo *repository.MessageRepository,
	folderRepo *repository.FolderRepository,
	labelRepo *repository.LabelRepository,
	analyticsRepo *repository.AnalyticsRepository,
	notifRepo *repository.NotificationProfileRepository,
	pollRepo *repository.PollRepository,
	redisClient *redis.Client,
	authzClient *authz.Client,
	log *zap.Logger,
) *ChatServer {
	return &ChatServer{
		convRepo:      convRepo,
		msgRepo:       msgRepo,
		folderRepo:    folderRepo,
		labelRepo:     labelRepo,
		analyticsRepo: analyticsRepo,
		notifRepo:     notifRepo,
		pollRepo:      pollRepo,
		redis:         redisClient,
		authz:         authzClient,
		log:           log,
	}
}

// ── Conversation RPCs ─────────────────────────────────────────────────────────

func (s *ChatServer) CreateConversation(ctx context.Context, req *chatpb.CreateConversationRequest) (*chatpb.CreateConversationResponse, error) {
	if req.CreatorId == "" {
		return nil, status.Error(codes.InvalidArgument, "creator_id required")
	}
	if len(req.MemberIds) == 0 {
		return nil, status.Error(codes.InvalidArgument, "at least one member required")
	}

	creatorID, err := uuid.Parse(req.CreatorId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid creator_id")
	}

	memberIDs := make([]uuid.UUID, 0, len(req.MemberIds))
	for _, idStr := range req.MemberIds {
		uid, err := uuid.Parse(idStr)
		if err != nil {
			return nil, status.Errorf(codes.InvalidArgument, "invalid member_id: %s", idStr)
		}
		memberIDs = append(memberIDs, uid)
	}

	convType := repository.ConversationDirect
	if req.Type == chatpb.ConversationType_GROUP {
		convType = repository.ConversationGroup
	}

	conv, err := s.convRepo.Create(ctx, convType, req.Name, creatorID, memberIDs)
	if err != nil {
		s.log.Error("create conversation", zap.Error(err))
		return nil, status.Error(codes.Internal, "failed to create conversation")
	}

	// Notify all conversation members via Redis pub/sub
	s.publishConvCreatedEvent(ctx, conv.ID.String(), req.CreatorId)

	return &chatpb.CreateConversationResponse{
		Conversation: convToProto(conv, nil, 0),
	}, nil
}

func (s *ChatServer) GetConversations(ctx context.Context, req *chatpb.GetConversationsRequest) (*chatpb.GetConversationsResponse, error) {
	uid, err := uuid.Parse(req.UserId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid user_id")
	}

	page := int(req.Page)
	if page < 1 {
		page = 1
	}
	pageSize := int(req.PageSize)
	if pageSize < 1 || pageSize > 100 {
		pageSize = 30
	}

	convs, total, err := s.convRepo.ListForUser(ctx, uid, page, pageSize)
	if err != nil {
		return nil, status.Error(codes.Internal, "failed to list conversations")
	}

	protoConvs := make([]*chatpb.Conversation, len(convs))
	for i, c := range convs {
		var lastMsgProto *chatpb.Message
		if lastMsg, err := s.msgRepo.GetLastMessage(ctx, c.ID); err == nil && lastMsg != nil {
			lastMsgProto = msgToProto(lastMsg)
		}
		protoConvs[i] = convToProto(c, lastMsgProto, 0)
	}

	return &chatpb.GetConversationsResponse{
		Conversations: protoConvs,
		Total:         int32(total),
	}, nil
}

func (s *ChatServer) GetConversation(ctx context.Context, req *chatpb.GetConversationRequest) (*chatpb.GetConversationResponse, error) {
	convID, err := uuid.Parse(req.ConversationId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid conversation_id")
	}

	conv, err := s.convRepo.GetByID(ctx, convID)
	if errors.Is(err, repository.ErrConversationNotFound) {
		return nil, status.Error(codes.NotFound, "conversation not found")
	}
	if err != nil {
		return nil, status.Error(codes.Internal, "failed to get conversation")
	}

	return &chatpb.GetConversationResponse{Conversation: convToProto(conv, nil, 0)}, nil
}

func (s *ChatServer) AddMember(ctx context.Context, req *chatpb.AddMemberRequest) (*chatpb.AddMemberResponse, error) {
	convID, _ := uuid.Parse(req.ConversationId)
	newMemberID, _ := uuid.Parse(req.NewMemberId)

	if err := s.convRepo.AddMember(ctx, convID, newMemberID); err != nil {
		return nil, status.Error(codes.Internal, "failed to add member")
	}
	return &chatpb.AddMemberResponse{Success: true}, nil
}

func (s *ChatServer) RemoveMember(ctx context.Context, req *chatpb.RemoveMemberRequest) (*chatpb.RemoveMemberResponse, error) {
	convID, _ := uuid.Parse(req.ConversationId)
	memberID, _ := uuid.Parse(req.MemberId)

	if err := s.convRepo.RemoveMember(ctx, convID, memberID); err != nil {
		return nil, status.Error(codes.Internal, "failed to remove member")
	}
	return &chatpb.RemoveMemberResponse{Success: true}, nil
}

// ── Message RPCs ──────────────────────────────────────────────────────────────

func (s *ChatServer) SendMessage(ctx context.Context, req *chatpb.SendMessageRequest) (*chatpb.SendMessageResponse, error) {
	convID, err := uuid.Parse(req.ConversationId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid conversation_id")
	}
	senderID, err := uuid.Parse(req.SenderId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid sender_id")
	}

	// Verify sender is a member
	isMember, err := s.convRepo.IsMember(ctx, convID, senderID)
	if err != nil {
		return nil, status.Error(codes.Internal, "membership check failed")
	}
	if !isMember {
		return nil, status.Error(codes.PermissionDenied, "you are not a member of this conversation")
	}

	msg := &repository.Message{
		ConversationID: convID,
		SenderID:       senderID,
		Content:        req.Content,
		Type:           protoToMessageType(req.Type),
		Status:         repository.MessageStatusSent,
		MediaURL:       req.MediaUrl,
		MediaMime:      req.MediaMime,
		MediaSize:      req.MediaSize,
		SendAt:         time.Now(),
	}

	if req.ParentId != "" {
		pid, err := uuid.Parse(req.ParentId)
		if err == nil {
			msg.ParentID = &pid
		}
	}
	if req.ExpiresAt > 0 {
		t := time.Unix(req.ExpiresAt, 0)
		msg.ExpiresAt = &t
	}

	for _, mid := range req.MentionedUserIds {
		uid, err := uuid.Parse(mid)
		if err == nil {
			msg.MentionedUserIDs = append(msg.MentionedUserIDs, uid)
		}
	}

	saved, err := s.msgRepo.Create(ctx, msg)
	if err != nil {
		s.log.Error("send message", zap.Error(err))
		return nil, status.Error(codes.Internal, "failed to send message")
	}

	// Publish to Redis for fan-out
	s.publishEvent(ctx, "new_message", saved.ID.String(), convID.String(), senderID.String())

	return &chatpb.SendMessageResponse{Message: msgToProto(saved)}, nil
}

func (s *ChatServer) ScheduleMessage(ctx context.Context, req *chatpb.ScheduleMessageRequest) (*chatpb.ScheduleMessageResponse, error) {
	if req.SendAt == 0 {
		return nil, status.Error(codes.InvalidArgument, "send_at is required for scheduled messages")
	}
	sendAt := time.Unix(req.SendAt, 0)
	if sendAt.Before(time.Now().Add(30 * time.Second)) {
		return nil, status.Error(codes.InvalidArgument, "send_at must be at least 30 seconds in the future")
	}

	convID, _ := uuid.Parse(req.ConversationId)
	senderID, _ := uuid.Parse(req.SenderId)

	msg := &repository.Message{
		ConversationID: convID,
		SenderID:       senderID,
		Content:        req.Content,
		Type:           repository.MessageTypeText,
		Status:         repository.MessageStatusScheduled,
		SendAt:         sendAt,
	}
	if req.ExpiresAt > 0 {
		t := time.Unix(req.ExpiresAt, 0)
		msg.ExpiresAt = &t
	}

	saved, err := s.msgRepo.Create(ctx, msg)
	if err != nil {
		return nil, status.Error(codes.Internal, "failed to schedule message")
	}

	s.log.Info("message scheduled",
		zap.String("msg_id", saved.ID.String()),
		zap.Time("send_at", sendAt),
	)

	return &chatpb.ScheduleMessageResponse{Message: msgToProto(saved)}, nil
}

func (s *ChatServer) GetMessages(ctx context.Context, req *chatpb.GetMessagesRequest) (*chatpb.GetMessagesResponse, error) {
	convID, err := uuid.Parse(req.ConversationId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid conversation_id")
	}

	var cursor *uuid.UUID
	if req.Cursor != "" {
		id, err := uuid.Parse(req.Cursor)
		if err == nil {
			cursor = &id
		}
	}

	limit := int(req.Limit)
	if limit <= 0 || limit > 100 {
		limit = 50
	}

	msgs, nextCursor, hasMore, err := s.msgRepo.List(ctx, convID, cursor, limit)
	if err != nil {
		return nil, status.Error(codes.Internal, "failed to fetch messages")
	}

	protoMsgs := make([]*chatpb.Message, len(msgs))
	for i, m := range msgs {
		protoMsgs[i] = msgToProto(m)
	}

	return &chatpb.GetMessagesResponse{
		Messages:   protoMsgs,
		NextCursor: nextCursor,
		HasMore:    hasMore,
	}, nil
}

func (s *ChatServer) GetThread(ctx context.Context, req *chatpb.GetThreadRequest) (*chatpb.GetThreadResponse, error) {
	parentID, err := uuid.Parse(req.ParentMessageId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid parent_message_id")
	}

	var cursor *uuid.UUID
	if req.Cursor != "" {
		id, _ := uuid.Parse(req.Cursor)
		cursor = &id
	}

	limit := int(req.Limit)
	if limit <= 0 || limit > 100 {
		limit = 50
	}

	replies, nextCursor, hasMore, err := s.msgRepo.GetThread(ctx, parentID, cursor, limit)
	if err != nil {
		return nil, status.Error(codes.Internal, "failed to fetch thread")
	}

	protoReplies := make([]*chatpb.Message, len(replies))
	for i, m := range replies {
		protoReplies[i] = msgToProto(m)
	}

	return &chatpb.GetThreadResponse{
		Replies:    protoReplies,
		NextCursor: nextCursor,
		HasMore:    hasMore,
	}, nil
}

func (s *ChatServer) EditMessage(ctx context.Context, req *chatpb.EditMessageRequest) (*chatpb.EditMessageResponse, error) {
	msgID, _ := uuid.Parse(req.MessageId)
	editorID, _ := uuid.Parse(req.EditorId)

	if req.Content == "" {
		return nil, status.Error(codes.InvalidArgument, "content cannot be empty")
	}

	msg, err := s.msgRepo.GetByID(ctx, msgID)
	if err != nil {
		return nil, status.Error(codes.NotFound, "message not found")
	}

	// Authorization Check (RBAC + ABAC)
	attrs := map[string]string{
		"resource.sender_id": msg.SenderID.String(),
		"resource.age_sec":   fmt.Sprintf("%f", time.Since(msg.CreatedAt).Seconds()),
	}
	allowed, reason, authErr := s.authz.Can(ctx, req.EditorId, "message:edit_own", msg.ID.String(), attrs)
	if authErr != nil {
		s.log.Error("failed to authorize message edit", zap.Error(authErr))
		return nil, status.Error(codes.Internal, "authorization check failed")
	}
	if !allowed {
		return nil, status.Error(codes.PermissionDenied, "unauthorized: "+reason)
	}

	// Enforce 15-minute edit window (aligning with WhatsApp & common messaging platforms)
	const maxEditWindow = 15 * time.Minute
	if time.Since(msg.CreatedAt) > maxEditWindow {
		return nil, status.Errorf(codes.FailedPrecondition, "messages can only be edited within %d minutes of sending", int(maxEditWindow.Minutes()))
	}

	updatedMsg, history, err := s.msgRepo.Edit(ctx, msgID, editorID, req.Content)
	if err != nil {
		return nil, status.Error(codes.Internal, "failed to edit message")
	}

	s.publishEvent(ctx, "message_edited", updatedMsg.ID.String(), updatedMsg.ConversationID.String(), editorID.String())

	protoHistory := make([]*chatpb.EditHistoryEntry, len(history))
	for i, h := range history {
		protoHistory[i] = &chatpb.EditHistoryEntry{
			Content:  h.Content,
			EditedAt: h.EditedAt.Unix(),
		}
	}

	return &chatpb.EditMessageResponse{
		Message:     msgToProto(updatedMsg),
		EditHistory: protoHistory,
	}, nil
}

func (s *ChatServer) DeleteMessage(ctx context.Context, req *chatpb.DeleteMessageRequest) (*chatpb.DeleteMessageResponse, error) {
	msgID, _ := uuid.Parse(req.MessageId)
	deleterID, _ := uuid.Parse(req.DeleterId)

	msg, err := s.msgRepo.GetByID(ctx, msgID)
	if err != nil {
		return nil, status.Error(codes.NotFound, "message not found")
	}

	// Authorization Check (RBAC + ABAC)
	attrs := map[string]string{
		"resource.sender_id": msg.SenderID.String(),
	}
	allowed, reason, authErr := s.authz.Can(ctx, req.DeleterId, "message:delete_own", msg.ID.String(), attrs)
	if authErr != nil {
		s.log.Error("failed to authorize message delete", zap.Error(authErr))
		return nil, status.Error(codes.Internal, "authorization check failed")
	}
	if !allowed {
		return nil, status.Error(codes.PermissionDenied, "unauthorized: "+reason)
	}

	if err := s.msgRepo.Delete(ctx, msgID, deleterID); err != nil {
		return nil, status.Error(codes.Internal, "failed to delete message")
	}

	s.publishEvent(ctx, "message_deleted", msgID.String(), msg.ConversationID.String(), deleterID.String())

	return &chatpb.DeleteMessageResponse{Success: true}, nil
}

func (s *ChatServer) ForwardMessage(ctx context.Context, req *chatpb.ForwardMessageRequest) (*chatpb.ForwardMessageResponse, error) {
	originalMsgID, err := uuid.Parse(req.MessageId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid message_id")
	}
	senderID, err := uuid.Parse(req.SenderId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid sender_id")
	}
	targetConvID, err := uuid.Parse(req.TargetConversationId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid target_conversation_id")
	}

	original, err := s.msgRepo.GetByID(ctx, originalMsgID)
	if err != nil {
		return nil, status.Error(codes.NotFound, "original message not found")
	}
	if original.IsDeleted {
		return nil, status.Error(codes.FailedPrecondition, "cannot forward a deleted message")
	}

	isMember, err := s.convRepo.IsMember(ctx, targetConvID, senderID)
	if err != nil {
		return nil, status.Error(codes.Internal, "membership check failed")
	}
	if !isMember {
		return nil, status.Error(codes.PermissionDenied, "you are not a member of the target conversation")
	}

	forwarded, err := s.msgRepo.Forward(ctx, original, targetConvID, senderID)
	if err != nil {
		s.log.Error("forward message", zap.Error(err))
		return nil, status.Error(codes.Internal, "failed to forward message")
	}

	if req.Content != "" {
		forwarded.Content = req.Content
	}

	s.publishEvent(ctx, "new_message", forwarded.ID.String(), targetConvID.String(), senderID.String())

	return &chatpb.ForwardMessageResponse{Message: msgToProto(forwarded)}, nil
}

func (s *ChatServer) AddReaction(ctx context.Context, req *chatpb.AddReactionRequest) (*chatpb.AddReactionResponse, error) {
	msgID, err1 := uuid.Parse(req.MessageId)
	userID, err2 := uuid.Parse(req.UserId)
	if err1 != nil || err2 != nil || msgID == uuid.Nil || userID == uuid.Nil {
		return &chatpb.AddReactionResponse{}, nil
	}

	rxn, err := s.msgRepo.AddReaction(ctx, msgID, userID, req.Emoji)
	if err != nil {
		s.log.Warn("add reaction ignored", zap.Error(err))
		return &chatpb.AddReactionResponse{}, nil
	}

	msg, _ := s.msgRepo.GetByID(ctx, msgID)
	if msg != nil {
		s.publishEvent(ctx, "reaction_added", msgID.String(), msg.ConversationID.String(), userID.String())
	}

	if rxn == nil {
		return &chatpb.AddReactionResponse{}, nil
	}

	return &chatpb.AddReactionResponse{
		Reaction: &chatpb.Reaction{
			UserId:    rxn.UserID.String(),
			Emoji:     rxn.Emoji,
			CreatedAt: rxn.CreatedAt.Unix(),
		},
	}, nil
}

func (s *ChatServer) RemoveReaction(ctx context.Context, req *chatpb.RemoveReactionRequest) (*chatpb.RemoveReactionResponse, error) {
	msgID, _ := uuid.Parse(req.MessageId)
	userID, _ := uuid.Parse(req.UserId)

	if err := s.msgRepo.RemoveReaction(ctx, msgID, userID, req.Emoji); err != nil {
		return nil, status.Error(codes.Internal, "failed to remove reaction")
	}

	msg, _ := s.msgRepo.GetByID(ctx, msgID)
	if msg != nil {
		s.publishEvent(ctx, "reaction_removed", msgID.String(), msg.ConversationID.String(), userID.String())
	}

	return &chatpb.RemoveReactionResponse{Success: true}, nil
}

func (s *ChatServer) MarkRead(ctx context.Context, req *chatpb.MarkReadRequest) (*chatpb.MarkReadResponse, error) {
	convID, _ := uuid.Parse(req.ConversationId)
	userID, _ := uuid.Parse(req.UserId)

	msgIDs := make([]uuid.UUID, 0, len(req.MessageIds))
	for _, idStr := range req.MessageIds {
		id, err := uuid.Parse(idStr)
		if err == nil {
			msgIDs = append(msgIDs, id)
		}
	}

	if err := s.msgRepo.MarkRead(ctx, convID, userID, msgIDs); err != nil {
		return nil, status.Error(codes.Internal, "failed to mark as read")
	}

	s.publishEvent(ctx, "read", "", convID.String(), userID.String())

	return &chatpb.MarkReadResponse{Success: true}, nil
}

func (s *ChatServer) PinMessage(ctx context.Context, req *chatpb.PinMessageRequest) (*chatpb.PinMessageResponse, error) {
	msgID, _ := uuid.Parse(req.MessageId)
	convID, _ := uuid.Parse(req.ConversationId)
	userID, _ := uuid.Parse(req.UserId)

	if err := s.msgRepo.Pin(ctx, msgID, convID, userID); err != nil {
		return nil, status.Error(codes.Internal, "failed to pin message")
	}

	s.publishEvent(ctx, "message_pinned", msgID.String(), convID.String(), userID.String())

	return &chatpb.PinMessageResponse{Success: true}, nil
}

func (s *ChatServer) UnpinMessage(ctx context.Context, req *chatpb.UnpinMessageRequest) (*chatpb.UnpinMessageResponse, error) {
	msgID, _ := uuid.Parse(req.MessageId)
	convID, _ := uuid.Parse(req.ConversationId)

	if err := s.msgRepo.Unpin(ctx, msgID, convID); err != nil {
		return nil, status.Error(codes.Internal, "failed to unpin message")
	}

	s.publishEvent(ctx, "message_unpinned", msgID.String(), convID.String(), "")

	return &chatpb.UnpinMessageResponse{Success: true}, nil
}

func (s *ChatServer) SearchMessages(ctx context.Context, req *chatpb.SearchMessagesRequest) (*chatpb.SearchMessagesResponse, error) {
	userID, _ := uuid.Parse(req.UserId)

	limit := int(req.Limit)
	if limit <= 0 || limit > 100 {
		limit = 20
	}

	filter := repository.SearchFilter{
		UserID:         userID,
		Query:          req.Query,
		ConversationID: req.ConversationId,
		Fuzzy:          true,
		Limit:          limit,
		Offset:         int(req.Offset),
	}

	msgs, total, err := s.msgRepo.SearchAdvanced(ctx, filter)
	if err != nil {
		return nil, status.Error(codes.Internal, "search failed")
	}

	protoMsgs := make([]*chatpb.Message, len(msgs))
	for i, m := range msgs {
		protoMsgs[i] = msgToProto(m)
	}

	return &chatpb.SearchMessagesResponse{
		Messages: protoMsgs,
		Total:    int32(total),
	}, nil
}

func (s *ChatServer) SendTypingIndicator(ctx context.Context, req *chatpb.SendTypingIndicatorRequest) (*chatpb.SendTypingIndicatorResponse, error) {
	channel := "chat:" + req.ConversationId
	typingStr := "false"
	if req.IsTyping {
		typingStr = "true"
	}
	payload := `{"event":"typing","conv_id":"` + req.ConversationId +
		`","actor_id":"` + req.UserId + `","is_typing":` + typingStr + `}`

	if s.redis != nil {
		if err := s.redis.Publish(ctx, channel, payload).Err(); err != nil {
			return nil, status.Error(codes.Internal, "failed to publish typing indicator")
		}
	}

	return &chatpb.SendTypingIndicatorResponse{Success: true}, nil
}

func (s *ChatServer) GetUnreadCounts(ctx context.Context, req *chatpb.GetUnreadCountsRequest) (*chatpb.GetUnreadCountsResponse, error) {
	userID, _ := uuid.Parse(req.UserId)

	convs, _, err := s.convRepo.ListForUser(ctx, userID, 1, 200)
	if err != nil {
		return nil, status.Error(codes.Internal, "failed to fetch conversations")
	}

	counts := make(map[string]int32, len(convs))
	for _, conv := range convs {
		n, err := s.msgRepo.GetUnreadCount(ctx, conv.ID, userID)
		if err == nil {
			counts[conv.ID.String()] = int32(n)
		}
	}

	return &chatpb.GetUnreadCountsResponse{Counts: counts}, nil
}

// StreamMessages opens a server-side stream.
// The gateway calls this once per connected user and fans out events over WebSocket.
func (s *ChatServer) StreamMessages(req *chatpb.StreamMessagesRequest, stream chatpb.ChatService_StreamMessagesServer) error {
	s.log.Info("user subscribed to message stream", zap.String("user_id", req.UserId))

	if s.redis == nil {
		<-stream.Context().Done()
		return stream.Context().Err()
	}

	sub := s.redis.PSubscribe(stream.Context(), "chat:*")
	defer sub.Close()

	for msg := range sub.Channel() {
		// Parse the payload to build a MessageEvent
		var payload map[string]string
		if err := json.Unmarshal([]byte(msg.Payload), &payload); err != nil {
			continue
		}

		// Security: Check if user is a member of the conversation
		convIDStr := payload["conv_id"]
		if convIDStr != "" {
			convID, err := uuid.Parse(convIDStr)
			if err == nil {
				userID, err := uuid.Parse(req.UserId)
				if err == nil {
					isMem, err := s.convRepo.IsMember(stream.Context(), convID, userID)
					if err != nil || !isMem {
						continue
					}
				} else {
					continue
				}
			} else {
				continue
			}
		}

		// Security: Check if this is a targeted private event
		targetUserIDStr := payload["target_user_id"]
		if targetUserIDStr != "" && targetUserIDStr != req.UserId {
			continue
		}

		event := &chatpb.MessageEvent{
			ActorId: payload["actor_id"],
		}

		switch payload["event"] {
		case "new_message":
			event.EventType = chatpb.EventType_EVENT_NEW_MESSAGE
		case "message_edited":
			event.EventType = chatpb.EventType_EVENT_MESSAGE_EDITED
		case "message_deleted":
			event.EventType = chatpb.EventType_EVENT_MESSAGE_DELETED
		case "reaction_added":
			event.EventType = chatpb.EventType_EVENT_REACTION_ADDED
		case "reaction_removed":
			event.EventType = chatpb.EventType_EVENT_REACTION_REMOVED
		case "typing":
			event.EventType = chatpb.EventType_EVENT_TYPING
		case "read":
			event.EventType = chatpb.EventType_EVENT_READ
		case "message_pinned", "message_unpinned":
			event.EventType = chatpb.EventType_EVENT_PINNED
		case "call_initiated":
			event.EventType = chatpb.EventType_EVENT_CALL_INITIATED
		case "call_accepted":
			event.EventType = chatpb.EventType_EVENT_CALL_ACCEPTED
		case "call_rejected":
			event.EventType = chatpb.EventType_EVENT_CALL_REJECTED
		case "call_ended":
			event.EventType = chatpb.EventType_EVENT_CALL_ENDED
		case "call_signaling":
			event.EventType = chatpb.EventType_EVENT_CALL_SIGNALING
		case "story_created":
			event.EventType = chatpb.EventType_EVENT_STORY_CREATED
		case "story_deleted":
			event.EventType = chatpb.EventType_EVENT_STORY_DELETED
		case "conversation_created":
			event.EventType = chatpb.EventType_EVENT_CONVERSATION_CREATED
		default:
			continue
		}

		// Optionally hydrate the full message from DB
		if msgID := payload["msg_id"]; msgID != "" {
			if id, err := uuid.Parse(msgID); err == nil {
				if m, err := s.msgRepo.GetByID(stream.Context(), id); err == nil {
					event.Message = msgToProto(m)
				}
			}
		}

		// Hydrate CallPayload if present
		if cpJSON := payload["call_payload"]; cpJSON != "" {
			var cp chatpb.CallPayload
			if err := json.Unmarshal([]byte(cpJSON), &cp); err == nil {
				event.CallPayload = &cp
			}
		}

		// Hydrate StoryPayload if present
		if spJSON := payload["story_payload"]; spJSON != "" {
			var sp chatpb.StoryPayload
			if err := json.Unmarshal([]byte(spJSON), &sp); err == nil {
				event.StoryPayload = &sp
			}
		}

		if err := stream.Send(event); err != nil {
			s.log.Info("stream client disconnected", zap.String("user_id", req.UserId))
			return nil
		}
	}

	return nil
}

// ── helpers ───────────────────────────────────────────────────────────────────

func (s *ChatServer) publishConvCreatedEvent(ctx context.Context, convID, actorID string) {
	if s.redis == nil {
		return
	}
	channel := "chat:" + convID
	payload := `{"event":"conversation_created","conv_id":"` + convID + `","actor_id":"` + actorID + `"}`
	if err := s.redis.Publish(ctx, channel, payload).Err(); err != nil {
		s.log.Warn("redis publish failed", zap.Error(err), zap.String("channel", channel))
	}
}

func (s *ChatServer) publishEvent(ctx context.Context, eventType, msgID, convID, actorID string) {
	if s.redis == nil {
		return
	}
	channel := "chat:" + convID
	payload := `{"event":"` + eventType + `","msg_id":"` + msgID +
		`","conv_id":"` + convID + `","actor_id":"` + actorID + `"}`
	if err := s.redis.Publish(ctx, channel, payload).Err(); err != nil {
		s.log.Warn("redis publish failed", zap.Error(err), zap.String("channel", channel))
	}
}

func convToProto(c *repository.Conversation, lastMsg *chatpb.Message, unread int) *chatpb.Conversation {
	memberIDs := make([]string, len(c.Members))
	for i, m := range c.Members {
		memberIDs[i] = m.String()
	}
	convType := chatpb.ConversationType_DIRECT
	if c.Type == repository.ConversationGroup {
		convType = chatpb.ConversationType_GROUP
	}
	return &chatpb.Conversation{
		Id:          c.ID.String(),
		Type:        convType,
		Name:        c.Name,
		AvatarUrl:   c.AvatarURL,
		MemberIds:   memberIDs,
		LastMessage: lastMsg,
		UnreadCount: int32(unread),
		CreatedAt:   c.CreatedAt.Unix(),
		UpdatedAt:   c.UpdatedAt.Unix(),
	}
}

func msgToProto(m *repository.Message) *chatpb.Message {
	pb := &chatpb.Message{
		Id:             m.ID.String(),
		ConversationId: m.ConversationID.String(),
		SenderId:       m.SenderID.String(),
		Content:        m.Content,
		MediaUrl:       m.MediaURL,
		MediaMime:      m.MediaMime,
		MediaSize:      m.MediaSize,
		ThreadCount:    int32(m.ThreadCount),
		SendAt:         m.SendAt.Unix(),
		IsPinned:       m.IsPinned,
		IsEdited:       m.IsEdited,
		IsDeleted:      m.IsDeleted,
		CreatedAt:      m.CreatedAt.Unix(),
		UpdatedAt:      m.UpdatedAt.Unix(),
	}

	if m.ParentID != nil {
		pb.ParentId = m.ParentID.String()
	}
	if m.ExpiresAt != nil {
		pb.ExpiresAt = m.ExpiresAt.Unix()
	}
	if m.ForwardedFromID != nil {
		pb.ForwardedFromId = m.ForwardedFromID.String()
	}
	if m.ForwardedFromConv != nil {
		pb.ForwardedFromConv = m.ForwardedFromConv.String()
	}
	if m.ForwardedFromSender != nil {
		pb.ForwardedFromSender = m.ForwardedFromSender.String()
	}
	for _, uid := range m.MentionedUserIDs {
		pb.MentionedUserIds = append(pb.MentionedUserIds, uid.String())
	}

	// Map type
	switch m.Type {
	case repository.MessageTypeImage:
		pb.Type = chatpb.MessageType_IMAGE
	case repository.MessageTypeVideo:
		pb.Type = chatpb.MessageType_VIDEO
	case repository.MessageTypeAudio:
		pb.Type = chatpb.MessageType_AUDIO
	case repository.MessageTypeVoice:
		pb.Type = chatpb.MessageType_VOICE
	case repository.MessageTypeFile:
		pb.Type = chatpb.MessageType_FILE
	default:
		pb.Type = chatpb.MessageType_TEXT
	}

	// Map status
	switch m.Status {
	case repository.MessageStatusDelivered:
		pb.Status = chatpb.MessageStatus_DELIVERED
	case repository.MessageStatusRead:
		pb.Status = chatpb.MessageStatus_READ
	case repository.MessageStatusScheduled:
		pb.Status = chatpb.MessageStatus_SCHEDULED
	case repository.MessageStatusFailed:
		pb.Status = chatpb.MessageStatus_FAILED
	default:
		pb.Status = chatpb.MessageStatus_SENT
	}

	for _, rx := range m.Reactions {
		pb.Reactions = append(pb.Reactions, &chatpb.Reaction{
			UserId:    rx.UserID.String(),
			Emoji:     rx.Emoji,
			CreatedAt: rx.CreatedAt.Unix(),
		})
	}

	for _, rd := range m.Reads {
		pb.Reads = append(pb.Reads, &chatpb.MessageRead{
			UserId: rd.UserID.String(),
			ReadAt: rd.ReadAt.Unix(),
		})
	}

	return pb
}

func protoToMessageType(t chatpb.MessageType) repository.MessageType {
	switch t {
	case chatpb.MessageType_IMAGE:
		return repository.MessageTypeImage
	case chatpb.MessageType_VIDEO:
		return repository.MessageTypeVideo
	case chatpb.MessageType_AUDIO:
		return repository.MessageTypeAudio
	case chatpb.MessageType_VOICE:
		return repository.MessageTypeVoice
	case chatpb.MessageType_FILE:
		return repository.MessageTypeFile
	default:
		return repository.MessageTypeText
	}
}

// ── Folders ───────────────────────────────────────────────────────────────────

func (s *ChatServer) CreateFolder(ctx context.Context, req *chatpb.CreateFolderRequest) (*chatpb.CreateFolderResponse, error) {
	if req.UserId == "" || req.Name == "" {
		return nil, status.Error(codes.InvalidArgument, "user_id and name required")
	}
	uid, err := uuid.Parse(req.UserId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid user_id")
	}
	f, err := s.folderRepo.Create(ctx, uid, req.Name, req.Icon, req.Color, int(req.SortOrder))
	if err != nil {
		return nil, status.Error(codes.Internal, "failed to create folder")
	}
	return &chatpb.CreateFolderResponse{Folder: folderToProto(f)}, nil
}

func (s *ChatServer) DeleteFolder(ctx context.Context, req *chatpb.DeleteFolderRequest) (*chatpb.DeleteFolderResponse, error) {
	uid, err := uuid.Parse(req.UserId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid user_id")
	}
	folderID, err := uuid.Parse(req.FolderId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid folder_id")
	}
	if err := s.folderRepo.Delete(ctx, uid, folderID); err != nil {
		if errors.Is(err, repository.ErrFolderNotFound) {
			return nil, status.Error(codes.NotFound, "folder not found")
		}
		return nil, status.Error(codes.Internal, "failed to delete folder")
	}
	return &chatpb.DeleteFolderResponse{Success: true}, nil
}

func (s *ChatServer) ListFolders(ctx context.Context, req *chatpb.ListFoldersRequest) (*chatpb.ListFoldersResponse, error) {
	uid, err := uuid.Parse(req.UserId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid user_id")
	}
	folders, err := s.folderRepo.ListByUser(ctx, uid)
	if err != nil {
		return nil, status.Error(codes.Internal, "failed to list folders")
	}
	out := make([]*chatpb.ChatFolder, len(folders))
	for i, f := range folders {
		out[i] = folderToProto(f)
	}
	return &chatpb.ListFoldersResponse{Folders: out}, nil
}

func (s *ChatServer) AddToFolder(ctx context.Context, req *chatpb.AddToFolderRequest) (*chatpb.AddToFolderResponse, error) {
	uid, err := uuid.Parse(req.UserId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid user_id")
	}
	folderID, err := uuid.Parse(req.FolderId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid folder_id")
	}
	convID, err := uuid.Parse(req.ConversationId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid conversation_id")
	}
	if err := s.folderRepo.AddConversation(ctx, uid, folderID, convID); err != nil {
		if errors.Is(err, repository.ErrFolderNotFound) {
			return nil, status.Error(codes.NotFound, "folder not found")
		}
		return nil, status.Error(codes.Internal, "failed to add to folder")
	}
	return &chatpb.AddToFolderResponse{Success: true}, nil
}

func (s *ChatServer) RemoveFromFolder(ctx context.Context, req *chatpb.RemoveFromFolderRequest) (*chatpb.RemoveFromFolderResponse, error) {
	uid, err := uuid.Parse(req.UserId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid user_id")
	}
	folderID, err := uuid.Parse(req.FolderId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid folder_id")
	}
	convID, err := uuid.Parse(req.ConversationId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid conversation_id")
	}
	if err := s.folderRepo.RemoveConversation(ctx, uid, folderID, convID); err != nil {
		if errors.Is(err, repository.ErrFolderNotFound) {
			return nil, status.Error(codes.NotFound, "folder not found")
		}
		return nil, status.Error(codes.Internal, "failed to remove from folder")
	}
	return &chatpb.RemoveFromFolderResponse{Success: true}, nil
}

// ── Labels ────────────────────────────────────────────────────────────────────

func (s *ChatServer) AddLabel(ctx context.Context, req *chatpb.AddLabelRequest) (*chatpb.AddLabelResponse, error) {
	if req.UserId == "" || req.MessageId == "" || req.Label == "" {
		return nil, status.Error(codes.InvalidArgument, "user_id, message_id, and label are required")
	}
	uid, err := uuid.Parse(req.UserId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid user_id")
	}
	msgID, err := uuid.Parse(req.MessageId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid message_id")
	}
	color := req.Color
	if color == "" {
		color = "#f59e0b"
	}
	l, err := s.labelRepo.Add(ctx, uid, msgID, req.Label, color)
	if err != nil {
		return nil, status.Error(codes.Internal, "failed to add label")
	}
	return &chatpb.AddLabelResponse{ChatLabel: labelToProto(l)}, nil
}

func (s *ChatServer) RemoveLabel(ctx context.Context, req *chatpb.RemoveLabelRequest) (*chatpb.RemoveLabelResponse, error) {
	uid, err := uuid.Parse(req.UserId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid user_id")
	}
	msgID, err := uuid.Parse(req.MessageId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid message_id")
	}
	if err := s.labelRepo.Remove(ctx, uid, msgID, req.Label); err != nil {
		if errors.Is(err, repository.ErrLabelNotFound) {
			return nil, status.Error(codes.NotFound, "label not found")
		}
		return nil, status.Error(codes.Internal, "failed to remove label")
	}
	return &chatpb.RemoveLabelResponse{Success: true}, nil
}

func (s *ChatServer) ListLabels(ctx context.Context, req *chatpb.ListLabelsRequest) (*chatpb.ListLabelsResponse, error) {
	uid, err := uuid.Parse(req.UserId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid user_id")
	}
	labels, err := s.labelRepo.List(ctx, uid, req.Label)
	if err != nil {
		return nil, status.Error(codes.Internal, "failed to list labels")
	}
	out := make([]*chatpb.ChatLabel, len(labels))
	for i, l := range labels {
		out[i] = labelToProto(l)
	}
	return &chatpb.ListLabelsResponse{Labels: out}, nil
}

// ── Analytics ─────────────────────────────────────────────────────────────────

func (s *ChatServer) GetChatAnalytics(ctx context.Context, req *chatpb.GetChatAnalyticsRequest) (*chatpb.GetChatAnalyticsResponse, error) {
	uid, err := uuid.Parse(req.UserId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid user_id")
	}
	var convID *uuid.UUID
	if req.ConversationId != "" {
		cID, err := uuid.Parse(req.ConversationId)
		if err != nil {
			return nil, status.Error(codes.InvalidArgument, "invalid conversation_id")
		}
		convID = &cID
	}

	if convID != nil {
		if err := s.analyticsRepo.Recompute(ctx, uid, *convID); err != nil {
			return nil, status.Error(codes.Internal, "failed to recompute analytics")
		}
	}

	rows, err := s.analyticsRepo.Get(ctx, uid, convID)
	if err != nil {
		return nil, status.Error(codes.Internal, "failed to fetch analytics")
	}
	out := make([]*chatpb.ConversationAnalytics, len(rows))
	for i, a := range rows {
		out[i] = analyticsToProto(a)
	}
	return &chatpb.GetChatAnalyticsResponse{Analytics: out}, nil
}

// ── Notification Profiles ─────────────────────────────────────────────────────

func (s *ChatServer) SetNotificationProfile(ctx context.Context, req *chatpb.SetNotificationProfileRequest) (*chatpb.SetNotificationProfileResponse, error) {
	if req.UserId == "" || req.Profile == nil {
		return nil, status.Error(codes.InvalidArgument, "user_id and profile required")
	}
	uid, err := uuid.Parse(req.UserId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid user_id")
	}
	convID, err := uuid.Parse(req.Profile.ConversationId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid conversation_id")
	}

	var muteUntil *time.Time
	if req.Profile.MuteUntil > 0 {
		t := time.Unix(req.Profile.MuteUntil, 0)
		muteUntil = &t
	}

	p := &repository.NotificationProfile{
		UserID:              uid,
		ConversationID:      convID,
		Muted:               req.Profile.Muted,
		MuteUntil:           muteUntil,
		Sound:               req.Profile.Sound,
		Vibration:           req.Profile.Vibration,
		Priority:            req.Profile.Priority,
		ShowPreview:         req.Profile.ShowPreview,
		NotifyOnMentionOnly: req.Profile.NotifyOnMentionOnly,
	}
	if err := s.notifRepo.Set(ctx, p); err != nil {
		return nil, status.Error(codes.Internal, "failed to set notification profile")
	}
	return &chatpb.SetNotificationProfileResponse{Success: true}, nil
}

func (s *ChatServer) GetNotificationProfiles(ctx context.Context, req *chatpb.GetNotificationProfilesRequest) (*chatpb.GetNotificationProfilesResponse, error) {
	uid, err := uuid.Parse(req.UserId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid user_id")
	}
	profiles, err := s.notifRepo.ListByUser(ctx, uid)
	if err != nil {
		return nil, status.Error(codes.Internal, "failed to list notification profiles")
	}
	out := make([]*chatpb.NotificationProfile, len(profiles))
	for i, p := range profiles {
		out[i] = notifToProto(p)
	}
	return &chatpb.GetNotificationProfilesResponse{Profiles: out}, nil
}

// ── Polls ─────────────────────────────────────────────────────────────────────

func (s *ChatServer) CreatePoll(ctx context.Context, req *chatpb.CreatePollRequest) (*chatpb.CreatePollResponse, error) {
	if req.ConversationId == "" || req.CreatedBy == "" || req.Question == "" {
		return nil, status.Error(codes.InvalidArgument, "conversation_id, created_by, and question are required")
	}
	if len(req.Options) < 2 || len(req.Options) > 10 {
		return nil, status.Error(codes.InvalidArgument, "a poll must have between 2 and 10 options")
	}

	convID, err := uuid.Parse(req.ConversationId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid conversation_id")
	}
	uid, err := uuid.Parse(req.CreatedBy)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid created_by")
	}

	isMember, err := s.convRepo.IsMember(ctx, convID, uid)
	if err != nil {
		return nil, status.Error(codes.Internal, "membership check failed")
	}
	if !isMember {
		return nil, status.Error(codes.PermissionDenied, "you are not a member of this conversation")
	}

	var expiresAt *time.Time
	if req.ExpiresAt > 0 {
		t := time.Unix(req.ExpiresAt, 0)
		expiresAt = &t
	}

	p := &repository.Poll{
		ConversationID: convID,
		CreatedBy:      uid,
		Question:       req.Question,
		IsAnonymous:    req.IsAnonymous,
		IsMultiple:     req.IsMultiple,
		ExpiresAt:      expiresAt,
	}

	poll, err := s.pollRepo.Create(ctx, p, req.Options)
	if err != nil {
		if errors.Is(err, repository.ErrInvalidPollOption) {
			return nil, status.Error(codes.InvalidArgument, "invalid poll options")
		}
		return nil, status.Error(codes.Internal, "failed to create poll")
	}

	s.publishEvent(ctx, "poll_created", poll.ID.String(), convID.String(), uid.String())

	return &chatpb.CreatePollResponse{Poll: pollToProto(poll, nil, nil)}, nil
}

func (s *ChatServer) GetPoll(ctx context.Context, req *chatpb.GetPollRequest) (*chatpb.GetPollResponse, error) {
	if req.PollId == "" || req.UserId == "" {
		return nil, status.Error(codes.InvalidArgument, "poll_id and user_id are required")
	}
	pollID, err := uuid.Parse(req.PollId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid poll_id")
	}
	uid, err := uuid.Parse(req.UserId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid user_id")
	}

	poll, err := s.pollRepo.Get(ctx, pollID)
	if err != nil {
		if errors.Is(err, repository.ErrPollNotFound) {
			return nil, status.Error(codes.NotFound, "poll not found")
		}
		return nil, status.Error(codes.Internal, "failed to fetch poll")
	}

	isMember, err := s.convRepo.IsMember(ctx, poll.ConversationID, uid)
	if err != nil {
		return nil, status.Error(codes.Internal, "membership check failed")
	}
	if !isMember {
		return nil, status.Error(codes.PermissionDenied, "you are not a member of this conversation")
	}

	options, err := s.pollRepo.ListOptions(ctx, pollID)
	if err != nil {
		return nil, status.Error(codes.Internal, "failed to fetch poll options")
	}

	requesterUUID := uid
	counts, err := s.pollRepo.GetVoteCounts(ctx, pollID, &requesterUUID, poll.IsAnonymous)
	if err != nil {
		return nil, status.Error(codes.Internal, "failed to fetch vote counts")
	}

	return &chatpb.GetPollResponse{Poll: pollToProto(poll, options, counts)}, nil
}

func (s *ChatServer) VotePoll(ctx context.Context, req *chatpb.VotePollRequest) (*chatpb.VotePollResponse, error) {
	if req.PollId == "" || req.UserId == "" || len(req.OptionIds) == 0 {
		return nil, status.Error(codes.InvalidArgument, "poll_id, user_id, and at least one option_id are required")
	}
	pollID, err := uuid.Parse(req.PollId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid poll_id")
	}
	uid, err := uuid.Parse(req.UserId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid user_id")
	}

	optionIDs := make([]uuid.UUID, 0, len(req.OptionIds))
	for _, idStr := range req.OptionIds {
		oid, err := uuid.Parse(idStr)
		if err != nil {
			return nil, status.Errorf(codes.InvalidArgument, "invalid option_id: %s", idStr)
		}
		optionIDs = append(optionIDs, oid)
	}

	poll, err := s.pollRepo.Get(ctx, pollID)
	if err != nil {
		if errors.Is(err, repository.ErrPollNotFound) {
			return nil, status.Error(codes.NotFound, "poll not found")
		}
		return nil, status.Error(codes.Internal, "failed to fetch poll")
	}

	isMember, err := s.convRepo.IsMember(ctx, poll.ConversationID, uid)
	if err != nil {
		return nil, status.Error(codes.Internal, "membership check failed")
	}
	if !isMember {
		return nil, status.Error(codes.PermissionDenied, "you are not a member of this conversation")
	}

	if poll.IsClosed {
		return nil, status.Error(codes.FailedPrecondition, "poll is closed")
	}

	if !poll.IsMultiple && len(optionIDs) > 1 {
		return nil, status.Error(codes.InvalidArgument, "single-choice poll accepts only one option")
	}

	if err := s.pollRepo.Vote(ctx, pollID, uid, optionIDs, poll.IsMultiple); err != nil {
		switch {
		case errors.Is(err, repository.ErrPollClosed):
			return nil, status.Error(codes.FailedPrecondition, "poll is closed")
		case errors.Is(err, repository.ErrInvalidPollOption):
			return nil, status.Error(codes.InvalidArgument, "invalid poll option")
		default:
			return nil, status.Error(codes.Internal, "failed to record vote")
		}
	}

	s.publishEvent(ctx, "poll_voted", pollID.String(), poll.ConversationID.String(), uid.String())

	options, err := s.pollRepo.ListOptions(ctx, pollID)
	if err != nil {
		return nil, status.Error(codes.Internal, "failed to refetch options")
	}
	counts, err := s.pollRepo.GetVoteCounts(ctx, pollID, &uid, poll.IsAnonymous)
	if err != nil {
		return nil, status.Error(codes.Internal, "failed to refetch counts")
	}
	poll, _ = s.pollRepo.Get(ctx, pollID)
	return &chatpb.VotePollResponse{Poll: pollToProto(poll, options, counts)}, nil
}

func (s *ChatServer) ClosePoll(ctx context.Context, req *chatpb.ClosePollRequest) (*chatpb.ClosePollResponse, error) {
	if req.PollId == "" || req.UserId == "" {
		return nil, status.Error(codes.InvalidArgument, "poll_id and user_id are required")
	}
	pollID, err := uuid.Parse(req.PollId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid poll_id")
	}
	uid, err := uuid.Parse(req.UserId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid user_id")
	}

	if err := s.pollRepo.Close(ctx, pollID, uid); err != nil {
		switch {
		case errors.Is(err, repository.ErrPollNotFound):
			return nil, status.Error(codes.NotFound, "poll not found")
		case errors.Is(err, repository.ErrNotPollCreator):
			return nil, status.Error(codes.PermissionDenied, "only the poll creator can close it")
		default:
			return nil, status.Error(codes.Internal, "failed to close poll")
		}
	}

	poll, _ := s.pollRepo.Get(ctx, pollID)
	options, _ := s.pollRepo.ListOptions(ctx, pollID)
	counts, _ := s.pollRepo.GetVoteCounts(ctx, pollID, &uid, poll.IsAnonymous)
	s.publishEvent(ctx, "poll_closed", pollID.String(), poll.ConversationID.String(), uid.String())
	return &chatpb.ClosePollResponse{Poll: pollToProto(poll, options, counts)}, nil
}

// ── Mappers ───────────────────────────────────────────────────────────────────

func folderToProto(f *repository.Folder) *chatpb.ChatFolder {
	items := make([]string, len(f.Items))
	for i, id := range f.Items {
		items[i] = id.String()
	}
	return &chatpb.ChatFolder{
		Id:              f.ID.String(),
		Name:            f.Name,
		Icon:            f.Icon,
		Color:           f.Color,
		SortOrder:       int32(f.SortOrder),
		ConversationIds: items,
	}
}

func labelToProto(l *repository.Label) *chatpb.ChatLabel {
	return &chatpb.ChatLabel{
		Id:        l.ID.String(),
		MessageId: l.MessageID.String(),
		Label:     l.Label,
		Color:     l.Color,
		CreatedAt: l.CreatedAt.Unix(),
	}
}

func analyticsToProto(a *repository.ConversationAnalytics) *chatpb.ConversationAnalytics {
	var lastActive int64
	if a.LastActiveAt != nil {
		lastActive = a.LastActiveAt.Unix()
	}
	return &chatpb.ConversationAnalytics{
		ConversationId: a.ConversationID.String(),
		TotalMessages:  int32(a.TotalMessages),
		AvgResponseMs:  a.AvgResponseMs,
		LastActiveAt:   lastActive,
		BusiestHour:    int32(a.BusiestHour),
	}
}

func notifToProto(p *repository.NotificationProfile) *chatpb.NotificationProfile {
	var muteUntil int64
	if p.MuteUntil != nil {
		muteUntil = p.MuteUntil.Unix()
	}
	return &chatpb.NotificationProfile{
		ConversationId:      p.ConversationID.String(),
		Muted:               p.Muted,
		MuteUntil:           muteUntil,
		Sound:               p.Sound,
		Vibration:           p.Vibration,
		Priority:            p.Priority,
		ShowPreview:         p.ShowPreview,
		NotifyOnMentionOnly: p.NotifyOnMentionOnly,
	}
}

func pollToProto(p *repository.Poll, options []*repository.PollOption, counts map[uuid.UUID]*repository.PollVoteCount) *chatpb.Poll {
	var expiresAt int64
	if p.ExpiresAt != nil {
		expiresAt = p.ExpiresAt.Unix()
	}

	protoOptions := make([]*chatpb.PollOption, 0)
	if options != nil {
		for _, opt := range options {
			pmc := counts[opt.ID]
			co := &chatpb.PollOption{
				Id:        opt.ID.String(),
				Text:      opt.Text,
				SortOrder: int32(opt.SortOrder),
			}
			if pmc != nil {
				co.VoteCount = int32(pmc.Count)
				voters := make([]string, len(pmc.VoterIDs))
				for i, vid := range pmc.VoterIDs {
					voters[i] = vid.String()
				}
				co.VoterUserIds = voters
			}
			protoOptions = append(protoOptions, co)
		}
	}

	return &chatpb.Poll{
		Id:             p.ID.String(),
		ConversationId: p.ConversationID.String(),
		CreatedBy:      p.CreatedBy.String(),
		Question:       p.Question,
		IsAnonymous:    p.IsAnonymous,
		IsMultiple:     p.IsMultiple,
		IsClosed:       p.IsClosed,
		ExpiresAt:      expiresAt,
		CreatedAt:      p.CreatedAt.Unix(),
		Options:        protoOptions,
	}
}
