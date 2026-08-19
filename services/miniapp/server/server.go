package server

import (
	"context"

	"github.com/jackc/pgx/v5/pgxpool"

	pb "gochat/gen/miniapp"
	"gochat/services/miniapp/repository"

	"go.uber.org/zap"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
)

type MiniAppServer struct {
	pb.UnimplementedMiniAppServiceServer
	repo *repository.MiniAppRepository
	log  *zap.Logger
}

func NewMiniAppServer(db *pgxpool.Pool, log *zap.Logger) *MiniAppServer {
	return &MiniAppServer{repo: repository.NewMiniAppRepository(db), log: log}
}

func (s *MiniAppServer) RegisterBot(ctx context.Context, req *pb.RegisterBotRequest) (*pb.RegisterBotResponse, error) {
	var cmds []*repository.BotCommand
	for _, c := range req.Commands {
		cmds = append(cmds, &repository.BotCommand{Command: c.Command, Description: c.Description})
	}
	bot, err := s.repo.RegisterBot(ctx, req.OwnerId, req.Username, req.DisplayName, req.Description, req.WebhookUrl, cmds)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "register bot: %v", err)
	}
	var pbCmds []*pb.BotCommand
	for _, c := range bot.Commands {
		pbCmds = append(pbCmds, &pb.BotCommand{Command: c.Command, Description: c.Description})
	}
	return &pb.RegisterBotResponse{Bot: &pb.Bot{
		Id: bot.ID, OwnerId: bot.OwnerID, Username: bot.Username,
		DisplayName: bot.DisplayName, Description: bot.Description,
		IsActive: bot.IsActive, Commands: pbCmds,
	}}, nil
}

func (s *MiniAppServer) ListBots(ctx context.Context, req *pb.ListBotsRequest) (*pb.ListBotsResponse, error) {
	limit := int(req.Limit)
	if limit <= 0 {
		limit = 20
	}
	bots, total, err := s.repo.ListBots(ctx, limit, int(req.Offset))
	if err != nil {
		return nil, status.Errorf(codes.Internal, "list bots: %v", err)
	}
	var pbBots []*pb.Bot
	for _, b := range bots {
		pbBots = append(pbBots, &pb.Bot{
			Id: b.ID, OwnerId: b.OwnerID, Username: b.Username,
			DisplayName: b.DisplayName, Description: b.Description,
			IsActive: b.IsActive, IsVerified: b.IsVerified,
		})
	}
	return &pb.ListBotsResponse{Bots: pbBots, Total: int32(total)}, nil
}

func (s *MiniAppServer) SendBotMessage(ctx context.Context, req *pb.SendBotMessageRequest) (*pb.SendBotMessageResponse, error) {
	s.log.Info("Bot message sent", zap.String("bot_id", req.BotId), zap.String("conv_id", req.ConversationId))
	return &pb.SendBotMessageResponse{Success: true}, nil
}

func (s *MiniAppServer) RegisterMiniApp(ctx context.Context, req *pb.RegisterMiniAppRequest) (*pb.RegisterMiniAppResponse, error) {
	app, err := s.repo.RegisterMiniApp(ctx, req.DeveloperId, req.Name, req.Description, req.IconUrl, req.ManifestUrl, req.Category)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "register miniapp: %v", err)
	}
	return &pb.RegisterMiniAppResponse{Miniapp: &pb.MiniApp{
		Id: app.ID, DeveloperId: app.DeveloperID, Name: app.Name,
		Description: app.Description, IconUrl: app.IconURL,
		ManifestUrl: app.ManifestURL, Category: app.Category,
	}}, nil
}

func (s *MiniAppServer) LaunchMiniApp(ctx context.Context, req *pb.LaunchMiniAppRequest) (*pb.LaunchMiniAppResponse, error) {
	sessionID, manifestURL, err := s.repo.LaunchMiniApp(ctx, req.UserId, req.MiniappId, req.ConversationId)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "launch miniapp: %v", err)
	}
	return &pb.LaunchMiniAppResponse{SessionId: sessionID, ManifestUrl: manifestURL}, nil
}

func (s *MiniAppServer) ListMiniApps(ctx context.Context, req *pb.ListMiniAppsRequest) (*pb.ListMiniAppsResponse, error) {
	limit := int(req.Limit)
	if limit <= 0 {
		limit = 20
	}
	apps, total, err := s.repo.ListMiniApps(ctx, limit, int(req.Offset), req.Category)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "list miniapps: %v", err)
	}
	var pbApps []*pb.MiniApp
	for _, a := range apps {
		pbApps = append(pbApps, &pb.MiniApp{
			Id: a.ID, DeveloperId: a.DeveloperID, Name: a.Name,
			Description: a.Description, IconUrl: a.IconURL,
			ManifestUrl: a.ManifestURL, Category: a.Category,
			IsApproved: a.IsApproved, InstallCount: int32(a.InstallCount),
		})
	}
	return &pb.ListMiniAppsResponse{Miniapps: pbApps, Total: int32(total)}, nil
}

func (s *MiniAppServer) RegisterWebhook(ctx context.Context, req *pb.RegisterWebhookRequest) (*pb.RegisterWebhookResponse, error) {
	wh, secret, err := s.repo.RegisterWebhook(ctx, req.UserId, req.Url, req.Events)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "register webhook: %v", err)
	}
	return &pb.RegisterWebhookResponse{
		Webhook: &pb.Webhook{Id: wh.ID, UserId: wh.UserID, Url: wh.URL, Events: wh.Events, IsActive: true, CreatedAt: wh.CreatedAt.Unix()},
		Secret:  secret,
	}, nil
}

func (s *MiniAppServer) ListWebhooks(ctx context.Context, req *pb.ListWebhooksRequest) (*pb.ListWebhooksResponse, error) {
	whs, err := s.repo.ListWebhooks(ctx, req.UserId)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "list webhooks: %v", err)
	}
	var pbWhs []*pb.Webhook
	for _, w := range whs {
		pbWhs = append(pbWhs, &pb.Webhook{Id: w.ID, UserId: w.UserID, Url: w.URL, IsActive: w.IsActive, CreatedAt: w.CreatedAt.Unix()})
	}
	return &pb.ListWebhooksResponse{Webhooks: pbWhs}, nil
}

func (s *MiniAppServer) DeleteWebhook(ctx context.Context, req *pb.DeleteWebhookRequest) (*pb.DeleteWebhookResponse, error) {
	if err := s.repo.DeleteWebhook(ctx, req.UserId, req.WebhookId); err != nil {
		return nil, status.Errorf(codes.Internal, "delete webhook: %v", err)
	}
	return &pb.DeleteWebhookResponse{Success: true}, nil
}

func (s *MiniAppServer) CreateAPIKey(ctx context.Context, req *pb.CreateAPIKeyRequest) (*pb.CreateAPIKeyResponse, error) {
	key, rawKey, err := s.repo.CreateAPIKey(ctx, req.UserId, req.Name, req.Permissions)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "create api key: %v", err)
	}
	return &pb.CreateAPIKeyResponse{
		Key:    &pb.APIKey{Id: key.ID, Name: key.Name, KeyPrefix: key.KeyPrefix, Permissions: key.Permissions, IsActive: true},
		RawKey: rawKey,
	}, nil
}

func (s *MiniAppServer) ListAPIKeys(ctx context.Context, req *pb.ListAPIKeysRequest) (*pb.ListAPIKeysResponse, error) {
	keys, err := s.repo.ListAPIKeys(ctx, req.UserId)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "list api keys: %v", err)
	}
	var pbKeys []*pb.APIKey
	for _, k := range keys {
		pbKeys = append(pbKeys, &pb.APIKey{Id: k.ID, Name: k.Name, KeyPrefix: k.KeyPrefix, IsActive: k.IsActive})
	}
	return &pb.ListAPIKeysResponse{Keys: pbKeys}, nil
}

func (s *MiniAppServer) RevokeAPIKey(ctx context.Context, req *pb.RevokeAPIKeyRequest) (*pb.RevokeAPIKeyResponse, error) {
	if err := s.repo.RevokeAPIKey(ctx, req.UserId, req.KeyId); err != nil {
		return nil, status.Errorf(codes.Internal, "revoke api key: %v", err)
	}
	return &pb.RevokeAPIKeyResponse{Success: true}, nil
}
