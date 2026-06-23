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

	mediapb "gochat/gen/media"
	"gochat/pkg/config"
	"gochat/pkg/logger"
	"gochat/services/media/server"
	"gochat/services/media/storage"
)

func main() {
	log := logger.New("media-service")
	defer log.Sync()

	cfg := config.Load()

	// ── MinIO Storage ─────────────────────────────────────────────────────────
	store, err := storage.NewMinIOStorage(
		cfg.MinIOEndpoint,
		cfg.MinIOAccessKey,
		cfg.MinIOSecretKey,
		cfg.MinioBucket,
		cfg.MinIOUseSSL,
		log,
	)
	if err != nil {
		log.Fatal("failed to connect to MinIO", zap.Error(err))
	}

	// ── gRPC Server ───────────────────────────────────────────────────────────
	grpcServer := grpc.NewServer(
		grpc.KeepaliveParams(keepalive.ServerParameters{
			MaxConnectionIdle: 5 * time.Minute,
			Time:              2 * time.Minute,
			Timeout:           20 * time.Second,
		}),
		grpc.MaxRecvMsgSize(110*1024*1024), // 110 MB (slightly above our 100 MB file cap)
		grpc.ChainUnaryInterceptor(
			func(ctx context.Context, req interface{}, info *grpc.UnaryServerInfo, handler grpc.UnaryHandler) (interface{}, error) {
				start := time.Now()
				resp, err := handler(ctx, req)
				log.Info("grpc call", zap.String("method", info.FullMethod), zap.Duration("d", time.Since(start)), zap.Error(err))
				return resp, err
			},
		),
	)

	mediapb.RegisterMediaServiceServer(grpcServer, server.New(store, log))
	reflection.Register(grpcServer)

	addr := fmt.Sprintf(":%s", cfg.GRPCPort)
	lis, err := net.Listen("tcp", addr)
	if err != nil {
		log.Fatal("failed to listen", zap.Error(err))
	}

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)

	go func() {
		log.Info("media service started", zap.String("addr", addr))
		if err := grpcServer.Serve(lis); err != nil {
			log.Fatal("gRPC server error", zap.Error(err))
		}
	}()

	<-quit
	log.Info("shutting down media service...")
	grpcServer.GracefulStop()
	log.Info("media service stopped")
}
