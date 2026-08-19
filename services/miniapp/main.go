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

	pb "gochat/gen/miniapp"
	"gochat/pkg/config"
	"gochat/pkg/database"
	"gochat/pkg/health"
	"gochat/pkg/logger"
	"gochat/services/miniapp/server"
)

func main() {
	log := logger.New("miniapp-service")
	defer log.Sync()
	cfg := config.Load()
	ctx := context.Background()

	db, err := database.NewPostgres(ctx, cfg.PostgresDSN, log)
	if err != nil {
		log.Fatal("failed to connect to database", zap.Error(err))
	}
	defer db.Close()

	if err := database.AutoMigrate(ctx, db, "miniapp", log); err != nil {
		log.Warn("boot auto-migration note", zap.Error(err))
	}

	// ── Health Check Server ──────────────────────────────────────────────────
	healthSrv := health.New("miniapp-service", cfg.HealthPort, log)
	healthSrv.AddCheck("postgres", func(ctx context.Context) error {
		return db.Ping(ctx)
	})
	healthSrv.Start()
	defer healthSrv.Stop()

	srv := server.NewMiniAppServer(db, log)
	lis, err := net.Listen("tcp", fmt.Sprintf(":%s", cfg.GRPCPort))
	if err != nil {
		log.Fatal("failed to listen", zap.Error(err))
	}

	grpcServer := grpc.NewServer()
	pb.RegisterMiniAppServiceServer(grpcServer, srv)

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	go func() {
		log.Info("MiniApp Service started", zap.String("port", cfg.GRPCPort))
		if err := grpcServer.Serve(lis); err != nil {
			log.Fatal("MiniApp Service failed", zap.Error(err))
		}
	}()
	<-quit
	log.Info("Shutting down MiniApp Service...")
	grpcServer.GracefulStop()
}
