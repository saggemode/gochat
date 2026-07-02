package main

import (
	"context"
	"fmt"
	"net"
	"os"
	"os/signal"
	"syscall"

	"go.uber.org/zap"
	"google.golang.org/grpc"

	pb "gochat/gen/ai"
	"gochat/pkg/config"
	"gochat/pkg/database"
	"gochat/pkg/health"
	"gochat/pkg/logger"
	"gochat/services/ai/server"
)

func main() {
	log := logger.New("ai-service")
	defer log.Sync()
	cfg := config.Load()
	ctx := context.Background()

	db, err := database.NewPostgres(ctx, cfg.PostgresDSN, log)
	if err != nil {
		log.Fatal("failed to connect to database", zap.Error(err))
	}
	defer db.Close()

	// ── Health Check Server ──────────────────────────────────────────────────
	healthSrv := health.New("ai-service", cfg.HealthPort, log)
	healthSrv.AddCheck("postgres", func(ctx context.Context) error {
		return db.Ping(ctx)
	})
	healthSrv.Start()
	defer healthSrv.Stop()

	srv := server.NewAIServer(db, log)
	lis, err := net.Listen("tcp", fmt.Sprintf(":%s", cfg.GRPCPort))
	if err != nil {
		log.Fatal("failed to listen", zap.Error(err))
	}

	grpcServer := grpc.NewServer()
	pb.RegisterAIServiceServer(grpcServer, srv)

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	go func() {
		log.Info("AI Service started", zap.String("port", cfg.GRPCPort))
		if err := grpcServer.Serve(lis); err != nil {
			log.Fatal("AI Service failed", zap.Error(err))
		}
	}()
	<-quit
	log.Info("Shutting down AI Service...")
	grpcServer.GracefulStop()
}
