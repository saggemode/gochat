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

	storypb "gochat/gen/story"
	"gochat/pkg/config"
	"gochat/pkg/database"
	"gochat/pkg/logger"
	"gochat/services/story/repository"
	"gochat/services/story/server"
)

func main() {
	log := logger.New("story-service")
	defer log.Sync()

	cfg := config.Load()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	// ── PostgreSQL ─────────────────────────────────────────────────────────────
	db, err := database.NewPostgres(ctx, cfg.PostgresDSN, log)
	if err != nil {
		log.Fatal("failed to connect to postgres", zap.Error(err))
	}
	defer db.Close()

	// ── Redis ─────────────────────────────────────────────────────────────────
	redisClient, err := database.NewRedis(ctx, cfg.RedisAddr, cfg.RedisPassword, cfg.RedisDB, log)
	if err != nil {
		log.Fatal("failed to connect to redis", zap.Error(err))
	}
	defer redisClient.Close()

	// ── Repository ────────────────────────────────────────────────────────────
	repo := repository.New(db)

	// ── Background Ticker ─────────────────────────────────────────────────────
	startCleanupTicker(repo, log, ctx)

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
	)

	storypb.RegisterStoryServiceServer(grpcServer, server.New(repo, redisClient, log))
	reflection.Register(grpcServer)

	// ── Listen ────────────────────────────────────────────────────────────────
	port := os.Getenv("GRPC_PORT")
	if port == "" {
		port = "50056"
	}
	addr := fmt.Sprintf(":%s", port)
	lis, err := net.Listen("tcp", addr)
	if err != nil {
		log.Fatal("failed to listen", zap.Error(err))
	}

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)

	go func() {
		log.Info("story service started", zap.String("addr", addr))
		if err := grpcServer.Serve(lis); err != nil {
			log.Fatal("gRPC server error", zap.Error(err))
		}
	}()

	<-quit
	log.Info("shutting down story service...")
	cancel() // Stops background ticker
	grpcServer.GracefulStop()
	log.Info("story service stopped")
}

func startCleanupTicker(repo *repository.StoryRepository, log *zap.Logger, ctx context.Context) {
	ticker := time.NewTicker(1 * time.Hour)
	go func() {
		// Run initial cleanup at boot
		affected, err := repo.DeleteExpiredStories(ctx)
		if err != nil {
			log.Error("failed to run initial expired stories cleanup", zap.Error(err))
		} else if affected > 0 {
			log.Info("cleaned up expired stories on startup", zap.Int64("deleted_count", affected))
		}

		for {
			select {
			case <-ticker.C:
				affected, err := repo.DeleteExpiredStories(ctx)
				if err != nil {
					log.Error("failed to delete expired stories", zap.Error(err))
				} else if affected > 0 {
					log.Info("cleaned up expired stories", zap.Int64("deleted_count", affected))
				}
			case <-ctx.Done():
				ticker.Stop()
				return
			}
		}
	}()
}

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
