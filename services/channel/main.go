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

	channelpb "gochat/gen/channel"
	"gochat/pkg/config"
	"gochat/pkg/database"
	"gochat/pkg/health"
	"gochat/pkg/logger"
	"gochat/services/channel/repository"
	"gochat/services/channel/server"
)

func main() {
	log := logger.New("channel-service")
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

	// ── Health Check Server ──────────────────────────────────────────────────
	healthSrv := health.New("channel-service", cfg.HealthPort, log)
	healthSrv.AddCheck("postgres", func(ctx context.Context) error {
		return db.Ping(ctx)
	})
	healthSrv.Start()
	defer healthSrv.Stop()

	// ── Repository ────────────────────────────────────────────────────────────
	repo := repository.New(db)

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

	channelpb.RegisterChannelServiceServer(grpcServer, server.New(repo, log))
	reflection.Register(grpcServer)

	// ── Listen ────────────────────────────────────────────────────────────────
	port := os.Getenv("GRPC_PORT")
	if port == "" {
		port = "50058"
	}
	addr := fmt.Sprintf(":%s", port)
	lis, err := net.Listen("tcp", addr)
	if err != nil {
		log.Fatal("failed to listen", zap.Error(err))
	}

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)

	go func() {
		log.Info("channel service started", zap.String("addr", addr))
		if err := grpcServer.Serve(lis); err != nil {
			log.Fatal("gRPC server error", zap.Error(err))
		}
	}()

	<-quit
	log.Info("shutting down channel service...")
	cancel()
	grpcServer.GracefulStop()
	log.Info("channel service stopped")
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
