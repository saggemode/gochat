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

	pb "gochat/gen/payment"
	"gochat/pkg/config"
	"gochat/pkg/database"
	"gochat/pkg/logger"
	"gochat/services/payment/server"
)

func main() {
	log := logger.New("payment-service")
	defer log.Sync()
	cfg := config.Load()
	ctx := context.Background()

	db, err := database.NewPostgres(ctx, cfg.PostgresDSN, log)
	if err != nil {
		log.Fatal("failed to connect to database", zap.Error(err))
	}
	defer db.Close()

	srv := server.NewPaymentServer(db, log)
	lis, err := net.Listen("tcp", fmt.Sprintf(":%s", cfg.GRPCPort))
	if err != nil {
		log.Fatal("failed to listen", zap.Error(err))
	}

	grpcServer := grpc.NewServer()
	pb.RegisterPaymentServiceServer(grpcServer, srv)

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	go func() {
		log.Info("Payment Service started", zap.String("port", cfg.GRPCPort))
		if err := grpcServer.Serve(lis); err != nil {
			log.Fatal("Payment Service failed", zap.Error(err))
		}
	}()
	<-quit
	log.Info("Shutting down Payment Service...")
	grpcServer.GracefulStop()
}
