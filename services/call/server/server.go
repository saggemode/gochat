package server

import (
	"context"
	"encoding/json"
	"time"

	"github.com/google/uuid"
	"github.com/redis/go-redis/v9"
	"go.uber.org/zap"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"

	callpb "gochat/gen/call"
	"gochat/services/call/repository"
)

type CallServer struct {
	callpb.UnimplementedCallServiceServer
	repo  *repository.CallRepository
	redis *redis.Client
	log   *zap.Logger
}

func New(repo *repository.CallRepository, redis *redis.Client, log *zap.Logger) *CallServer {
	return &CallServer{
		repo:  repo,
		redis: redis,
		log:   log,
	}
}

func (s *CallServer) StartCall(ctx context.Context, req *callpb.StartCallRequest) (*callpb.StartCallResponse, error) {
	callerID, err := uuid.Parse(req.CallerId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid caller_id")
	}
	receiverID, err := uuid.Parse(req.ReceiverId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid receiver_id")
	}

	callType := "voice"
	if req.Type == callpb.CallType_VIDEO {
		callType = "video"
	}

	c := &repository.CallLog{
		ID:        uuid.New(),
		CallerID:  callerID,
		ReceiverID: receiverID,
		Type:      callType,
		Status:    "dialing",
		StartTime: time.Now(),
	}

	err = s.repo.Create(ctx, c)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "log call: %v", err)
	}

	// Fetch hydrated log (names & avatars)
	hydrated, err := s.repo.GetByID(ctx, c.ID)
	if err != nil {
		// Fallback to minimal if fetch fails
		hydrated = c
	}

	pbCall := mapCallLog(hydrated)

	// Publish real-time event to Redis targeted to the receiver
	cpPayload := map[string]string{
		"call_id":     pbCall.Id,
		"caller_id":   pbCall.CallerId,
		"receiver_id": pbCall.ReceiverId,
		"type":        callType,
		"status":      "dialing",
	}
	cpJSON, _ := json.Marshal(cpPayload)

	eventPayload := map[string]string{
		"event":          "call_initiated",
		"actor_id":       pbCall.CallerId,
		"target_user_id": pbCall.ReceiverId,
		"call_payload":   string(cpJSON),
	}
	eventJSON, _ := json.Marshal(eventPayload)

	// Publish to Redis channel (starts with "chat:" so chat service matches it)
	if err := s.redis.Publish(ctx, "chat:calls", string(eventJSON)).Err(); err != nil {
		s.log.Warn("failed to publish call_initiated event", zap.Error(err))
	}

	return &callpb.StartCallResponse{
		Call: pbCall,
	}, nil
}

func (s *CallServer) AcceptCall(ctx context.Context, req *callpb.AcceptCallRequest) (*callpb.AcceptCallResponse, error) {
	callID, err := uuid.Parse(req.CallId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid call_id")
	}

	c, err := s.repo.GetByID(ctx, callID)
	if err != nil {
		return nil, status.Errorf(codes.NotFound, "call log not found: %v", err)
	}

	if c.Status != "dialing" {
		return nil, status.Error(codes.FailedPrecondition, "call is not in dialing state")
	}

	err = s.repo.UpdateStatus(ctx, callID, "active")
	if err != nil {
		return nil, status.Errorf(codes.Internal, "update status: %v", err)
	}

	c.Status = "active"
	pbCall := mapCallLog(c)

	// Notify caller that call was accepted
	cpPayload := map[string]string{
		"call_id":     pbCall.Id,
		"caller_id":   pbCall.CallerId,
		"receiver_id": pbCall.ReceiverId,
		"type":        c.Type,
		"status":      "active",
	}
	cpJSON, _ := json.Marshal(cpPayload)

	eventPayload := map[string]string{
		"event":          "call_accepted",
		"actor_id":       c.ReceiverID.String(),
		"target_user_id": c.CallerID.String(),
		"call_payload":   string(cpJSON),
	}
	eventJSON, _ := json.Marshal(eventPayload)

	if err := s.redis.Publish(ctx, "chat:calls", string(eventJSON)).Err(); err != nil {
		s.log.Warn("failed to publish call_accepted event", zap.Error(err))
	}

	return &callpb.AcceptCallResponse{
		Call: pbCall,
	}, nil
}

func (s *CallServer) RejectCall(ctx context.Context, req *callpb.RejectCallRequest) (*callpb.RejectCallResponse, error) {
	callID, err := uuid.Parse(req.CallId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid call_id")
	}

	c, err := s.repo.GetByID(ctx, callID)
	if err != nil {
		return nil, status.Errorf(codes.NotFound, "call log not found: %v", err)
	}

	statusStr := "rejected"
	if req.IsBusy {
		statusStr = "busy"
	}

	err = s.repo.UpdateStatus(ctx, callID, statusStr)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "update status: %v", err)
	}

	c.Status = statusStr
	pbCall := mapCallLog(c)

	// Notify caller call was rejected
	cpPayload := map[string]string{
		"call_id":     pbCall.Id,
		"caller_id":   pbCall.CallerId,
		"receiver_id": pbCall.ReceiverId,
		"type":        c.Type,
		"status":      statusStr,
	}
	cpJSON, _ := json.Marshal(cpPayload)

	eventPayload := map[string]string{
		"event":          "call_rejected",
		"actor_id":       c.ReceiverID.String(),
		"target_user_id": c.CallerID.String(),
		"call_payload":   string(cpJSON),
	}
	eventJSON, _ := json.Marshal(eventPayload)

	if err := s.redis.Publish(ctx, "chat:calls", string(eventJSON)).Err(); err != nil {
		s.log.Warn("failed to publish call_rejected event", zap.Error(err))
	}

	return &callpb.RejectCallResponse{
		Call: pbCall,
	}, nil
}

func (s *CallServer) EndCall(ctx context.Context, req *callpb.EndCallRequest) (*callpb.EndCallResponse, error) {
	callID, err := uuid.Parse(req.CallId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid call_id")
	}
	userID, err := uuid.Parse(req.UserId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid user_id")
	}

	c, err := s.repo.GetByID(ctx, callID)
	if err != nil {
		return nil, status.Errorf(codes.NotFound, "call log not found: %v", err)
	}

	// Calculate call duration
	now := time.Now()
	dur := 0
	if c.Status == "active" {
		dur = int(now.Sub(c.StartTime).Seconds())
	}

	// Update call status in database
	err = s.repo.EndCall(ctx, callID, now, dur)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "end call: %v", err)
	}

	c.Status = "ended"
	c.EndTime = &now
	c.DurationSec = dur
	pbCall := mapCallLog(c)

	// Determine recipient of ended notification (the other party)
	targetUser := c.ReceiverID.String()
	if userID == c.ReceiverID {
		targetUser = c.CallerID.String()
	}

	cpPayload := map[string]string{
		"call_id":     pbCall.Id,
		"caller_id":   pbCall.CallerId,
		"receiver_id": pbCall.ReceiverId,
		"type":        c.Type,
		"status":      "ended",
	}
	cpJSON, _ := json.Marshal(cpPayload)

	eventPayload := map[string]string{
		"event":          "call_ended",
		"actor_id":       userID.String(),
		"target_user_id": targetUser,
		"call_payload":   string(cpJSON),
	}
	eventJSON, _ := json.Marshal(eventPayload)

	if err := s.redis.Publish(ctx, "chat:calls", string(eventJSON)).Err(); err != nil {
		s.log.Warn("failed to publish call_ended event", zap.Error(err))
	}

	return &callpb.EndCallResponse{
		Call: pbCall,
	}, nil
}

func (s *CallServer) SendSignalingMessage(ctx context.Context, req *callpb.SendSignalingMessageRequest) (*callpb.SendSignalingMessageResponse, error) {
	// Relays WebRTC SDP or ICE candidates to the specific target user
	cpPayload := map[string]string{
		"call_id":     req.CallId,
		"caller_id":   req.SenderId,
		"receiver_id": req.ReceiverId,
		"status":      "signaling",
		"sdp":         req.Sdp,
		"candidate":   req.Candidate,
		"type":        req.Type, // offer, answer, ice-candidate
	}
	cpJSON, _ := json.Marshal(cpPayload)

	eventPayload := map[string]string{
		"event":          "call_signaling",
		"actor_id":       req.SenderId,
		"target_user_id": req.ReceiverId,
		"call_payload":   string(cpJSON),
	}
	eventJSON, _ := json.Marshal(eventPayload)

	if err := s.redis.Publish(ctx, "chat:calls", string(eventJSON)).Err(); err != nil {
		return nil, status.Errorf(codes.Internal, "relay signaling: %v", err)
	}

	return &callpb.SendSignalingMessageResponse{
		Success: true,
	}, nil
}

func (s *CallServer) GetCallHistory(ctx context.Context, req *callpb.GetCallHistoryRequest) (*callpb.GetCallHistoryResponse, error) {
	userID, err := uuid.Parse(req.UserId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid user_id")
	}

	limit := int(req.PageSize)
	if limit <= 0 {
		limit = 20
	}
	offset := int(req.Page-1) * limit
	if offset < 0 {
		offset = 0
	}

	history, total, err := s.repo.GetHistory(ctx, userID, offset, limit)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "get history: %v", err)
	}

	pbCalls := make([]*callpb.CallLog, len(history))
	for i, c := range history {
		pbCalls[i] = mapCallLog(c)
	}

	return &callpb.GetCallHistoryResponse{
		Calls: pbCalls,
		Total: int32(total),
	}, nil
}

func mapCallLog(c *repository.CallLog) *callpb.CallLog {
	t := callpb.CallType_VOICE
	if c.Type == "video" {
		t = callpb.CallType_VIDEO
	}

	st := callpb.CallStatus_DIALING
	switch c.Status {
	case "active":
		st = callpb.CallStatus_ACTIVE
	case "rejected":
		st = callpb.CallStatus_REJECTED
	case "missed":
		st = callpb.CallStatus_MISSED
	case "ended":
		st = callpb.CallStatus_ENDED
	case "busy":
		st = callpb.CallStatus_BUSY
	}

	var endTime int64
	if c.EndTime != nil {
		endTime = c.EndTime.Unix()
	}

	return &callpb.CallLog{
		Id:                c.ID.String(),
		CallerId:          c.CallerID.String(),
		CallerName:        c.CallerName,
		CallerAvatarUrl:   c.CallerAvatarURL,
		ReceiverId:        c.ReceiverID.String(),
		ReceiverName:      c.ReceiverName,
		ReceiverAvatarUrl: c.ReceiverAvatarURL,
		Type:              t,
		Status:            st,
		StartTime:         c.StartTime.Unix(),
		EndTime:           endTime,
		DurationSec:       int32(c.DurationSec),
	}
}
