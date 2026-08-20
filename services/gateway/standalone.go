package main

import (
	"context"
	"net"
	"os"
	"strings"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/redis/go-redis/v9"
	"go.uber.org/zap"
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
	"google.golang.org/grpc/test/bufconn"

	aipb "gochat/gen/ai"
	authpb "gochat/gen/auth"
	authzpb "gochat/gen/authz"
	businesspb "gochat/gen/business"
	callpb "gochat/gen/call"
	channelpb "gochat/gen/channel"
	chatpb "gochat/gen/chat"
	grouppb "gochat/gen/group"
	mediapb "gochat/gen/media"
	miniapppb "gochat/gen/miniapp"
	socialpb "gochat/gen/social"
	storypb "gochat/gen/story"

	"gochat/pkg/authz"
	"gochat/pkg/config"
	"gochat/pkg/crypto"
	"gochat/pkg/database"
	"gochat/pkg/jwtutil"

	aiserver "gochat/services/ai/server"
	authrepo "gochat/services/auth/repository"
	authserver "gochat/services/auth/server"
	authzrepo "gochat/services/authz/repository"
	authzserver "gochat/services/authz/server"
	businessserver "gochat/services/business/server"
	callrepo "gochat/services/call/repository"
	callserver "gochat/services/call/server"
	channelrepo "gochat/services/channel/repository"
	channelserver "gochat/services/channel/server"
	chatrepo "gochat/services/chat/repository"
	chatserver "gochat/services/chat/server"
	grouprepo "gochat/services/group/repository"
	groupserver "gochat/services/group/server"
	mediaserver "gochat/services/media/server"
	mediastorage "gochat/services/media/storage"
	miniappserver "gochat/services/miniapp/server"
	socialserver "gochat/services/social/server"
	storyrepo "gochat/services/story/repository"
	storyserver "gochat/services/story/server"
)

const (
	neonAuthDSN     = "postgres://neondb_owner:npg_GfPRWH95xneU@ep-dark-forest-a2188hsu-pooler.eu-central-1.aws.neon.tech/auth-gochat-db?sslmode=require"
	neonChatDSN     = "postgres://neondb_owner:npg_GfPRWH95xneU@ep-dark-forest-a2188hsu-pooler.eu-central-1.aws.neon.tech/chat-gochat-db?sslmode=require"
	neonAuthzDSN    = "postgres://neondb_owner:npg_GfPRWH95xneU@ep-dark-forest-a2188hsu-pooler.eu-central-1.aws.neon.tech/authz-gochat-db?sslmode=require"
	neonGroupDSN    = "postgres://neondb_owner:npg_GfPRWH95xneU@ep-dark-forest-a2188hsu-pooler.eu-central-1.aws.neon.tech/group-gochat-db?sslmode=require"
	neonStoryDSN    = "postgres://neondb_owner:npg_GfPRWH95xneU@ep-dark-forest-a2188hsu-pooler.eu-central-1.aws.neon.tech/story-gochat-db?sslmode=require"
	neonCallDSN     = "postgres://neondb_owner:npg_GfPRWH95xneU@ep-dark-forest-a2188hsu-pooler.eu-central-1.aws.neon.tech/call-gochat-db?sslmode=require"
	neonChannelDSN  = "postgres://neondb_owner:npg_GfPRWH95xneU@ep-dark-forest-a2188hsu-pooler.eu-central-1.aws.neon.tech/channel-gochat-db?sslmode=require"
	neonSocialDSN   = "postgres://neondb_owner:npg_GfPRWH95xneU@ep-dark-forest-a2188hsu-pooler.eu-central-1.aws.neon.tech/social-gochat-db?sslmode=require"
	neonMiniAppDSN  = "postgres://neondb_owner:npg_GfPRWH95xneU@ep-dark-forest-a2188hsu-pooler.eu-central-1.aws.neon.tech/miniapp-gochat-db?sslmode=require"
	neonBusinessDSN = "postgres://neondb_owner:npg_GfPRWH95xneU@ep-dark-forest-a2188hsu-pooler.eu-central-1.aws.neon.tech/business-gochat-db?sslmode=require"
)

func getServiceDSN(envKey, fallbackNeonDSN string) string {
	if v := os.Getenv(envKey); v != "" {
		return v
	}
	if v := os.Getenv("POSTGRES_DSN"); v != "" && !strings.Contains(v, "localhost:5432") {
		return v
	}
	return fallbackNeonDSN
}

func isHostResolvable(addr string) bool {
	host, _, err := net.SplitHostPort(addr)
	if err != nil {
		host = addr
	}
	if host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "" {
		conn, err := net.DialTimeout("tcp", addr, 100*time.Millisecond)
		if err != nil {
			return false
		}
		conn.Close()
		return true
	}
	ctx, cancel := context.WithTimeout(context.Background(), 500*time.Millisecond)
	defer cancel()
	var r net.Resolver
	addrs, err := r.LookupHost(ctx, host)
	if err != nil || len(addrs) == 0 {
		return false
	}
	conn, err := net.DialTimeout("tcp", addr, 300*time.Millisecond)
	if err != nil {
		return false
	}
	conn.Close()
	return true
}

func startInProcessGRPC(
	ctx context.Context,
	cfg *config.Config,
	redisClient *redis.Client,
	log *zap.Logger,
) (*grpc.ClientConn, error) {
	lis := bufconn.Listen(1024 * 1024)
	s := grpc.NewServer()

	// 1. Auth Service
	authDSN := getServiceDSN("AUTH_DB_DSN", neonAuthDSN)
	authDB, err := database.NewPostgres(ctx, authDSN, log)
	if err != nil {
		log.Warn("in-process fallback: auth postgres error", zap.Error(err))
	} else {
		_ = database.AutoMigrate(ctx, authDB, "auth", log)
		var enc *crypto.Encryptor
		if cfg.E2EEKeyEncryptionKey != "" {
			enc, _ = crypto.NewEncryptor(cfg.E2EEKeyEncryptionKey)
		}
		authRepoInst := authrepo.NewUserRepositoryWithEncryptor(authDB, enc)
		jwtMgr := jwtutil.NewManager(cfg.JWTSecret, cfg.JWTExpiryDuration, cfg.JWTRefreshExpiry)
		authpb.RegisterAuthServiceServer(s, authserver.New(authRepoInst, jwtMgr, redisClient, log))
		log.Info("in-process Auth Service registered")
	}

	// 2. Authz Service
	authzDSN := getServiceDSN("AUTHZ_DB_DSN", neonAuthzDSN)
	authzDB, err := database.NewPostgres(ctx, authzDSN, log)
	if err == nil {
		_ = database.AutoMigrate(ctx, authzDB, "authz", log)
		authzRepoInst := authzrepo.NewAuthzRepository(authzDB)
		authzpb.RegisterAuthzServiceServer(s, authzserver.New(authzRepoInst, log))
	}

	// 3. Chat Service
	chatDSN := getServiceDSN("CHAT_DB_DSN", neonChatDSN)
	chatDB, err := database.NewPostgres(ctx, chatDSN, log)
	if err != nil {
		log.Warn("in-process fallback: chat postgres error", zap.Error(err))
	} else {
		_ = database.AutoMigrate(ctx, chatDB, "chat", log)
		convRepo := chatrepo.NewConversationRepository(chatDB)
		msgRepo := chatrepo.NewMessageRepository(chatDB)
		folderRepo := chatrepo.NewFolderRepository(chatDB)
		labelRepo := chatrepo.NewLabelRepository(chatDB)
		analyticsRepo := chatrepo.NewAnalyticsRepository(chatDB)
		notifRepo := chatrepo.NewNotificationProfileRepository(chatDB)
		pollRepo := chatrepo.NewPollRepository(chatDB)

		var authzCl *authz.Client
		if authzDB != nil {
			// In-process authz client if needed
		}

		chatpb.RegisterChatServiceServer(s, chatserver.New(
			convRepo, msgRepo, folderRepo, labelRepo, analyticsRepo, notifRepo, pollRepo,
			redisClient, authzCl, log,
		))
		log.Info("in-process Chat Service registered")
	}

	// 4. Group Service
	groupDSN := getServiceDSN("GROUP_DB_DSN", neonGroupDSN)
	if groupDB, err := database.NewPostgres(ctx, groupDSN, log); err == nil {
		_ = database.AutoMigrate(ctx, groupDB, "grp", log)
		grouppb.RegisterGroupServiceServer(s, groupserver.New(grouprepo.New(groupDB), log))
	}

	// 5. Story Service
	storyDSN := getServiceDSN("STORY_DB_DSN", neonStoryDSN)
	if storyDB, err := database.NewPostgres(ctx, storyDSN, log); err == nil {
		_ = database.AutoMigrate(ctx, storyDB, "story", log)
		storypb.RegisterStoryServiceServer(s, storyserver.New(storyrepo.New(storyDB), redisClient, log))
	}

	// 6. Call Service
	callDSN := getServiceDSN("CALL_DB_DSN", neonCallDSN)
	if callDB, err := database.NewPostgres(ctx, callDSN, log); err == nil {
		_ = database.AutoMigrate(ctx, callDB, "call", log)
		callpb.RegisterCallServiceServer(s, callserver.New(callrepo.New(callDB), redisClient, log))
	}

	// 7. Channel Service
	channelDSN := getServiceDSN("CHANNEL_DB_DSN", neonChannelDSN)
	if channelDB, err := database.NewPostgres(ctx, channelDSN, log); err == nil {
		_ = database.AutoMigrate(ctx, channelDB, "channel", log)
		channelpb.RegisterChannelServiceServer(s, channelserver.New(channelrepo.New(channelDB), log))
	}

	// 8. Social Service
	socialDSN := getServiceDSN("SOCIAL_DB_DSN", neonSocialDSN)
	if socialDB, err := database.NewPostgres(ctx, socialDSN, log); err == nil {
		_ = database.AutoMigrate(ctx, socialDB, "social", log)
		socialpb.RegisterSocialServiceServer(s, socialserver.NewSocialServer(socialDB, log))
	}

	// 9. MiniApp Service
	miniAppDSN := getServiceDSN("MINIAPP_DB_DSN", neonMiniAppDSN)
	if miniAppDB, err := database.NewPostgres(ctx, miniAppDSN, log); err == nil {
		_ = database.AutoMigrate(ctx, miniAppDB, "miniapp", log)
		miniapppb.RegisterMiniAppServiceServer(s, miniappserver.NewMiniAppServer(miniAppDB, log))
	}

	// 10. Business Service
	bizDSN := getServiceDSN("BUSINESS_DB_DSN", neonBusinessDSN)
	if bizDB, err := database.NewPostgres(ctx, bizDSN, log); err == nil {
		_ = database.AutoMigrate(ctx, bizDB, "business", log)
		businesspb.RegisterBusinessServiceServer(s, businessserver.NewBusinessServer(bizDB, log))
	}

	// 11. AI Service
	aipb.RegisterAIServiceServer(s, aiserver.NewAIServer(nil, log))

	// 12. Media Service
	var minioStore *mediastorage.MinIOStorage
	if cfg.MinIOEndpoint != "" {
		minioStore, _ = mediastorage.NewMinIOStorage(
			cfg.MinIOEndpoint,
			cfg.MinIOAccessKey,
			cfg.MinIOSecretKey,
			cfg.MinioBucket,
			cfg.MinIOUseSSL,
			log,
		)
	}
	mediapb.RegisterMediaServiceServer(s, mediaserver.New(minioStore, log))

	go func() {
		if err := s.Serve(lis); err != nil {
			log.Warn("in-process gRPC server stopped", zap.Error(err))
		}
	}()

	return grpc.DialContext(
		ctx,
		"bufnet",
		grpc.WithContextDialer(func(context.Context, string) (net.Conn, error) {
			return lis.Dial()
		}),
		grpc.WithTransportCredentials(insecure.NewCredentials()),
	)
}

func closePoolSafe(p *pgxpool.Pool) {
	if p != nil {
		p.Close()
	}
}
