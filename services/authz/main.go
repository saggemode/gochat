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

	authzpb "gochat/gen/authz"
	"gochat/pkg/config"
	"gochat/pkg/database"
	"gochat/pkg/health"
	"gochat/pkg/logger"
	"gochat/services/authz/repository"
	"gochat/services/authz/seeder"
	"gochat/services/authz/server"
)

func main() {
	log := logger.New("authz-service")
	defer log.Sync()

	cfg := config.Load()

	// ── Database ──────────────────────────────────────────────────────────────
	ctx := context.Background()

	db, err := database.NewPostgres(ctx, cfg.PostgresDSN, log)
	if err != nil {
		log.Fatal("failed to connect to postgres", zap.Error(err))
	}
	defer db.Close()

	if err := database.AutoMigrate(ctx, db, "authz", log); err != nil {
		log.Warn("boot auto-migration note", zap.Error(err))
	}

	// ── Health Check Server ──────────────────────────────────────────────────
	healthSrv := health.New("authz-service", cfg.HealthPort, log)
	healthSrv.AddCheck("postgres", func(ctx context.Context) error {
		return db.Ping(ctx)
	})
	healthSrv.Start()
	defer healthSrv.Stop()

	// ── Repository ────────────────────────────────────────────────────────────
	repo := repository.NewAuthzRepository(db)

	// ── Seed roles/permissions/policies ───────────────────────────────────────
	if err := seeder.Seed(ctx, repo, log); err != nil {
		log.Fatal("failed to seed authorization rules", zap.Error(err))
	}

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

	authzpb.RegisterAuthzServiceServer(grpcServer, server.New(repo, log))
	reflection.Register(grpcServer)

	// ── Listen ────────────────────────────────────────────────────────────────
	// Use GRPC_PORT or fallback to 50054
	port := os.Getenv("GRPC_PORT")
	if port == "" {
		port = "50054"
	}
	addr := fmt.Sprintf(":%s", port)
	lis, err := net.Listen("tcp", addr)
	if err != nil {
		log.Fatal("failed to listen", zap.Error(err))
	}

	// ── Graceful shutdown ─────────────────────────────────────────────────────
	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)

	go func() {
		log.Info("authz service started", zap.String("addr", addr))
		if err := grpcServer.Serve(lis); err != nil {
			log.Fatal("gRPC server error", zap.Error(err))
		}
	}()

	<-quit
	log.Info("shutting down authz service...")
	grpcServer.GracefulStop()
	log.Info("authz service stopped")
}

// ── Interceptors ──────────────────────────────────────────────────────────────

func loggingInterceptor(log *zap.Logger) grpc.UnaryServerInterceptor {
	return func(ctx context.Context, req interface{}, info *grpc.UnaryServerInfo, handler grpc.UnaryHandler) (interface{}, error) {
		start := time.Now()
		resp, err := handler(ctx, req)
		log.Info("grpc call",
			zap.String("method", info.FullMethod),
			zap.Duration("duration", time.Since(start)),
			zap.Error(err),
		)
		return resp, err
	}
}

func recoveryInterceptor(log *zap.Logger) grpc.UnaryServerInterceptor {
	return func(ctx context.Context, req interface{}, info *grpc.UnaryServerInfo, handler grpc.UnaryHandler) (resp interface{}, err error) {
		defer func() {
			if r := recover(); r != nil {
				log.Error("panic recovered", zap.Any("panic", r), zap.String("method", info.FullMethod))
				err = fmt.Errorf("internal error")
			}
		}()
		return handler(ctx, req)
	}
}
