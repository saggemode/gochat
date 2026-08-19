package main

import (
	"context"
	"fmt"
	"net"
	"os"
	"os/signal"
	"syscall"
	"time"

	"go.uber.org/zap"
	"google.golang.org/grpc"
	"google.golang.org/grpc/keepalive"
	"google.golang.org/grpc/reflection"

	chatpb "gochat/gen/chat"
	"gochat/pkg/authz"
	"gochat/pkg/config"
	"gochat/pkg/database"
	"gochat/pkg/health"
	"gochat/pkg/logger"
	"gochat/services/chat/repository"
	"gochat/services/chat/scheduler"
	"gochat/services/chat/server"
)

func main() {
	log := logger.New("chat-service")
	defer log.Sync()

	cfg := config.Load()
	ctx := context.Background()

	// ── PostgreSQL ─────────────────────────────────────────────────────────────
	db, err := database.NewPostgres(ctx, cfg.PostgresDSN, log)
	if err != nil {
		log.Fatal("failed to connect to postgres", zap.Error(err))
	}
	defer db.Close()

	if err := database.AutoMigrate(ctx, db, "chat", log); err != nil {
		log.Warn("boot auto-migration note", zap.Error(err))
	}

	// ── Redis ─────────────────────────────────────────────────────────────────
	redisClient, err := database.NewRedis(ctx, cfg.RedisAddr, cfg.RedisPassword, cfg.RedisDB, log)
	if err != nil {
		log.Fatal("failed to connect to redis", zap.Error(err))
	}
	defer redisClient.Close()

	// ── Health Check Server ──────────────────────────────────────────────────
	healthSrv := health.New("chat-service", cfg.HealthPort, log)
	healthSrv.AddCheck("postgres", func(ctx context.Context) error {
		return db.Ping(ctx)
	})
	healthSrv.AddCheck("redis", func(ctx context.Context) error {
		return redisClient.Ping(ctx).Err()
	})
	healthSrv.Start()
	defer healthSrv.Stop()

	// ── Authz Client ──────────────────────────────────────────────────────────
	authzClient, err := authz.NewClient(cfg.AuthzGRPCAddr)
	if err != nil {
		log.Fatal("failed to connect to authz service", zap.Error(err))
	}
	defer authzClient.Close()

	// ── Repositories ──────────────────────────────────────────────────────────
	convRepo := repository.NewConversationRepository(db)
	msgRepo := repository.NewMessageRepository(db)
	folderRepo := repository.NewFolderRepository(db)
	labelRepo := repository.NewLabelRepository(db)
	analyticsRepo := repository.NewAnalyticsRepository(db)
	notifRepo := repository.NewNotificationProfileRepository(db)
	pollRepo := repository.NewPollRepository(db)

	// ── Scheduler ─────────────────────────────────────────────────────────────
	sched := scheduler.New(msgRepo, redisClient, log)
	sched.Start()
	defer sched.Stop()

	// ── gRPC Server ───────────────────────────────────────────────────────────
	grpcServer := grpc.NewServer(
		grpc.KeepaliveParams(keepalive.ServerParameters{
			MaxConnectionIdle: 5 * time.Minute,
			Time:              2 * time.Minute,
			Timeout:           20 * time.Second,
		}),
		grpc.ChainUnaryInterceptor(
			loggingInterceptor(log),
			recoveryInterceptor(log),
		),
		grpc.ChainStreamInterceptor(
			streamLoggingInterceptor(log),
		),
	)

	chatpb.RegisterChatServiceServer(grpcServer, server.New(convRepo, msgRepo, folderRepo, labelRepo, analyticsRepo, notifRepo, pollRepo, redisClient, authzClient, log))

	reflection.Register(grpcServer)

	// ── Listen ────────────────────────────────────────────────────────────────
	addr := fmt.Sprintf(":%s", cfg.GRPCPort)
	lis, err := net.Listen("tcp", addr)
	if err != nil {
		log.Fatal("failed to listen", zap.Error(err))
	}

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)

	go func() {
		log.Info("chat service started", zap.String("addr", addr))
		if err := grpcServer.Serve(lis); err != nil {
			log.Fatal("gRPC server error", zap.Error(err))
		}
	}()

	<-quit
	log.Info("shutting down chat service...")
	grpcServer.GracefulStop()
	log.Info("chat service stopped")
}

// ── Interceptors ──────────────────────────────────────────────────────────────

func loggingInterceptor(log *zap.Logger) grpc.UnaryServerInterceptor {
	return func(ctx context.Context, req interface{}, info *grpc.UnaryServerInfo, handler grpc.UnaryHandler) (interface{}, error) {
		start := time.Now()
		resp, err := handler(ctx, req)
		log.Info("grpc unary", zap.String("method", info.FullMethod), zap.Duration("duration", time.Since(start)), zap.Error(err))
		return resp, err
	}
}

func recoveryInterceptor(log *zap.Logger) grpc.UnaryServerInterceptor {
	return func(ctx context.Context, req interface{}, info *grpc.UnaryServerInfo, handler grpc.UnaryHandler) (resp interface{}, err error) {
		defer func() {
			if r := recover(); r != nil {
				log.Error("panic recovered", zap.Any("panic", r))
				err = fmt.Errorf("internal error")
			}
		}()
		return handler(ctx, req)
	}
}

func streamLoggingInterceptor(log *zap.Logger) grpc.StreamServerInterceptor {
	return func(srv interface{}, ss grpc.ServerStream, info *grpc.StreamServerInfo, handler grpc.StreamHandler) error {
		log.Info("grpc stream opened", zap.String("method", info.FullMethod))
		err := handler(srv, ss)
		log.Info("grpc stream closed", zap.String("method", info.FullMethod), zap.Error(err))
		return err
	}
}
