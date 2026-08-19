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
	"google.golang.org/grpc/credentials/insecure"
	"google.golang.org/grpc/keepalive"
	"google.golang.org/grpc/reflection"

	aipb "gochat/gen/ai"
	chatpb "gochat/gen/chat"
	"gochat/pkg/config"
	"gochat/pkg/health"
	"gochat/pkg/logger"
	"gochat/services/ai/server"
)

func main() {
	log := logger.New("ai-service")
	defer log.Sync()

	cfg := config.Load()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	_ = ctx

	// ── Health Check Server ──────────────────────────────────────────────────
	healthPort := os.Getenv("HEALTH_PORT")
	if healthPort == "" {
		healthPort = "8089"
	}
	healthSrv := health.New("ai-service", healthPort, log)
	healthSrv.Start()
	defer healthSrv.Stop()

	// ── Connect to Chat Service (Optional) ──────────────────────────────────
	var chatClient chatpb.ChatServiceClient
	if cfg.ChatGRPCAddr != "" {
		chatConn, err := grpc.Dial(cfg.ChatGRPCAddr, grpc.WithTransportCredentials(insecure.NewCredentials()))
		if err == nil {
			defer chatConn.Close()
			chatClient = chatpb.NewChatServiceClient(chatConn)
			log.Info("connected to chat service", zap.String("addr", cfg.ChatGRPCAddr))
		} else {
			log.Warn("could not connect to chat service", zap.Error(err))
		}
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

	aiServer := server.NewAIServer(chatClient, log)
	aipb.RegisterAIServiceServer(grpcServer, aiServer)
	reflection.Register(grpcServer)

	// ── Listen ────────────────────────────────────────────────────────────────
	port := os.Getenv("GRPC_PORT")
	if port == "" {
		port = "50059"
	}
	addr := fmt.Sprintf(":%s", port)
	lis, err := net.Listen("tcp", addr)
	if err != nil {
		log.Fatal("failed to listen", zap.Error(err))
	}

	log.Info("AI service started", zap.String("addr", addr))

	// ── Graceful Shutdown ─────────────────────────────────────────────────────
	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, syscall.SIGINT, syscall.SIGTERM)

	go func() {
		<-sigCh
		log.Info("shutting down AI service...")
		grpcServer.GracefulStop()
		cancel()
	}()

	if err := grpcServer.Serve(lis); err != nil {
		log.Fatal("AI service crashed", zap.Error(err))
	}
}

func loggingInterceptor(log *zap.Logger) grpc.UnaryServerInterceptor {
	return func(
		ctx context.Context,
		req interface{},
		info *grpc.UnaryServerInfo,
		handler grpc.UnaryHandler,
	) (interface{}, error) {
		start := time.Now()
		resp, err := handler(ctx, req)
		log.Info("grpc request",
			zap.String("method", info.FullMethod),
			zap.Duration("duration", time.Since(start)),
			zap.Error(err),
		)
		return resp, err
	}
}

func recoveryInterceptor(log *zap.Logger) grpc.UnaryServerInterceptor {
	return func(
		ctx context.Context,
		req interface{},
		info *grpc.UnaryServerInfo,
		handler grpc.UnaryHandler,
	) (resp interface{}, err error) {
		defer func() {
			if r := recover(); r != nil {
				log.Error("grpc panic recovered", zap.Any("panic", r))
				err = fmt.Errorf("internal server error")
			}
		}()
		return handler(ctx, req)
	}
}
