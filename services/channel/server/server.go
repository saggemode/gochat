package server

import (
	"context"
	"time"

	"github.com/google/uuid"
	"go.uber.org/zap"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"

	channelpb "gochat/gen/channel"
	"gochat/services/channel/repository"
)

type ChannelServer struct {
	channelpb.UnimplementedChannelServiceServer
	repo *repository.ChannelRepository
	log  *zap.Logger
}

func New(repo *repository.ChannelRepository, log *zap.Logger) *ChannelServer {
	return &ChannelServer{
		repo: repo,
		log:  log,
	}
}

func (s *ChannelServer) CreateChannel(ctx context.Context, req *channelpb.CreateChannelRequest) (*channelpb.CreateChannelResponse, error) {
	creatorID, err := uuid.Parse(req.CreatorId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid creator_id")
	}
	if req.Name == "" {
		return nil, status.Error(codes.InvalidArgument, "name cannot be empty")
	}

	c, err := s.repo.CreateChannel(ctx, req.Name, req.Description, creatorID)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "create channel failed: %v", err)
	}

	return &channelpb.CreateChannelResponse{
		Channel: &channelpb.Channel{
			Id:               c.ID.String(),
			Name:             c.Name,
			Description:      c.Description,
			CreatedBy:        c.CreatedBy.String(),
			CreatedAt:        c.CreatedAt.Unix(),
			SubscribersCount: int32(c.SubscribersCount),
		},
	}, nil
}

func (s *ChannelServer) DeleteChannel(ctx context.Context, req *channelpb.DeleteChannelRequest) (*channelpb.DeleteChannelResponse, error) {
	channelID, err := uuid.Parse(req.Id)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid channel_id")
	}
	ownerID, err := uuid.Parse(req.OwnerId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid owner_id")
	}

	err = s.repo.DeleteChannel(ctx, channelID, ownerID)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "delete channel failed: %v", err)
	}

	return &channelpb.DeleteChannelResponse{Success: true}, nil
}

func (s *ChannelServer) SubscribeChannel(ctx context.Context, req *channelpb.SubscribeChannelRequest) (*channelpb.SubscribeChannelResponse, error) {
	channelID, err := uuid.Parse(req.ChannelId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid channel_id")
	}
	userID, err := uuid.Parse(req.UserId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid user_id")
	}

	err = s.repo.SubscribeChannel(ctx, channelID, userID)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "subscribe channel failed: %v", err)
	}

	return &channelpb.SubscribeChannelResponse{Success: true}, nil
}

func (s *ChannelServer) UnsubscribeChannel(ctx context.Context, req *channelpb.UnsubscribeChannelRequest) (*channelpb.UnsubscribeChannelResponse, error) {
	channelID, err := uuid.Parse(req.ChannelId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid channel_id")
	}
	userID, err := uuid.Parse(req.UserId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid user_id")
	}

	err = s.repo.UnsubscribeChannel(ctx, channelID, userID)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "unsubscribe channel failed: %v", err)
	}

	return &channelpb.UnsubscribeChannelResponse{Success: true}, nil
}

func (s *ChannelServer) PublishChannelMessage(ctx context.Context, req *channelpb.PublishChannelMessageRequest) (*channelpb.PublishChannelMessageResponse, error) {
	channelID, err := uuid.Parse(req.ChannelId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid channel_id")
	}
	senderID, err := uuid.Parse(req.SenderId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid sender_id")
	}

	m, err := s.repo.PublishChannelMessage(ctx, channelID, senderID, req.Content, req.Type, req.MediaUrl, req.MediaMime, req.MediaSize)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "publish channel message failed: %v", err)
	}

	return &channelpb.PublishChannelMessageResponse{
		Message: &channelpb.ChannelMessage{
			Id:        m.ID.String(),
			ChannelId: m.ChannelID.String(),
			SenderId:  m.SenderID.String(),
			Content:   m.Content,
			Type:      m.Type,
			MediaUrl:  m.MediaURL,
			MediaMime: m.MediaMime,
			MediaSize: m.MediaSize,
			CreatedAt: m.CreatedAt.Unix(),
		},
	}, nil
}

func (s *ChannelServer) GetChannelMessages(ctx context.Context, req *channelpb.GetChannelMessagesRequest) (*channelpb.GetChannelMessagesResponse, error) {
	channelID, err := uuid.Parse(req.ChannelId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid channel_id")
	}

	var beforeTime time.Time
	if req.BeforeTimestamp > 0 {
		beforeTime = time.Unix(req.BeforeTimestamp, 0)
	}

	limit := int(req.Limit)
	if limit <= 0 {
		limit = 20
	} else if limit > 100 {
		limit = 100
	}

	messages, err := s.repo.GetChannelMessages(ctx, channelID, limit, beforeTime)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "get channel messages failed: %v", err)
	}

	pbMessages := make([]*channelpb.ChannelMessage, len(messages))
	for i, m := range messages {
		pbMessages[i] = &channelpb.ChannelMessage{
			Id:        m.ID.String(),
			ChannelId: m.ChannelID.String(),
			SenderId:  m.SenderID.String(),
			Content:   m.Content,
			Type:      m.Type,
			MediaUrl:  m.MediaURL,
			MediaMime: m.MediaMime,
			MediaSize: m.MediaSize,
			CreatedAt: m.CreatedAt.Unix(),
		}
	}

	return &channelpb.GetChannelMessagesResponse{Messages: pbMessages}, nil
}

func (s *ChannelServer) GetChannelMetadata(ctx context.Context, req *channelpb.GetChannelMetadataRequest) (*channelpb.GetChannelMetadataResponse, error) {
	channelID, err := uuid.Parse(req.ChannelId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid channel_id")
	}

	c, err := s.repo.GetChannelMetadata(ctx, channelID)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "get channel metadata failed: %v", err)
	}

	return &channelpb.GetChannelMetadataResponse{
		Channel: &channelpb.Channel{
			Id:               c.ID.String(),
			Name:             c.Name,
			Description:      c.Description,
			CreatedBy:        c.CreatedBy.String(),
			CreatedAt:        c.CreatedAt.Unix(),
			SubscribersCount: int32(c.SubscribersCount),
		},
	}, nil
}

func (s *ChannelServer) ListChannels(ctx context.Context, req *channelpb.ListChannelsRequest) (*channelpb.ListChannelsResponse, error) {
	var userID uuid.UUID
	var err error
	if req.UserId != "" {
		userID, err = uuid.Parse(req.UserId)
		if err != nil {
			return nil, status.Error(codes.InvalidArgument, "invalid user_id")
		}
	}

	channels, err := s.repo.ListChannels(ctx, userID)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "list channels failed: %v", err)
	}

	pbChannels := make([]*channelpb.Channel, len(channels))
	for i, c := range channels {
		pbChannels[i] = &channelpb.Channel{
			Id:               c.ID.String(),
			Name:             c.Name,
			Description:      c.Description,
			CreatedBy:        c.CreatedBy.String(),
			CreatedAt:        c.CreatedAt.Unix(),
			SubscribersCount: int32(c.SubscribersCount),
		}
	}

	return &channelpb.ListChannelsResponse{Channels: pbChannels}, nil
}
