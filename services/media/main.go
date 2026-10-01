package main

import (
	"context"
	"fmt"
	"net"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"go.uber.org/zap"
	"google.golang.org/grpc"
	"google.golang.org/grpc/keepalive"
	"google.golang.org/grpc/reflection"

	mediapb "gochat/gen/media"
	"gochat/pkg/config"
	"gochat/pkg/database"
	"gochat/pkg/health"
	"gochat/pkg/logger"
	"gochat/services/media/server"
	"gochat/services/media/storage"
)

func main() {
	log := logger.New("media-service")
	defer log.Sync()

	cfg := config.Load()
	ctx := context.Background()

	// ── Storage Providers ────────────────────────────────────────────────────
	// 1. MinIO / S3 Storage
	var store *storage.MinIOStorage
	store, err := storage.NewMinIOStorage(
		cfg.MinIOEndpoint,
		cfg.MinIOAccessKey,
		cfg.MinIOSecretKey,
		cfg.MinioBucket,
		cfg.MinIOUseSSL,
		log,
	)
	if err != nil {
		log.Warn("MinIO storage unavailable", zap.Error(err))
	}

	// 2. Telegram CDN Storage
	tgStore := storage.NewTelegramStorage(
		cfg.TelegramAPIID,
		cfg.TelegramAPIHash,
		cfg.TelegramBotToken,
		cfg.TelegramChannelID,
		log,
	)
	if tgStore.IsConfigured() {
		log.Info("Telegram CDN Storage initialized")
	}

	// 3. PostgreSQL Database Storage (Neon / Cloud Postgres)
	dsn := cfg.PostgresDSN
	if envDSN := os.Getenv("MEDIA_DB_DSN"); envDSN != "" {
		dsn = envDSN
	} else if chatDSN := os.Getenv("CHAT_DB_DSN"); chatDSN != "" {
		dsn = chatDSN
	} else if groupDSN := os.Getenv("GROUP_DB_DSN"); groupDSN != "" {
		dsn = groupDSN
	}

	var dbStore *storage.DatabaseStorage
	if dsn != "" && !strings.Contains(dsn, "localhost:5432") {
		db, dbErr := database.NewPostgres(ctx, dsn, log)
		if dbErr != nil {
			log.Warn("PostgreSQL media storage connection failed, skipping DB storage", zap.Error(dbErr))
		} else {
			defer db.Close()
			if err := database.AutoMigrate(ctx, db, "media", log); err != nil {
				log.Warn("boot auto-migration note", zap.Error(err))
			}
			dbStore = storage.NewDatabaseStorage(db, log)
			log.Info("PostgreSQL Media Storage initialized")
		}
	} else {
		// Attempt local or default postgres connection if configured
		if db, dbErr := database.NewPostgres(ctx, dsn, log); dbErr == nil {
			defer db.Close()
			_ = database.AutoMigrate(ctx, db, "media", log)
			dbStore = storage.NewDatabaseStorage(db, log)
			log.Info("Local PostgreSQL Media Storage initialized")
		}
	}

	// 4. Local Disk Storage (emergency fallback)
	localStore := storage.NewLocalStorage(log)

	// ── Health Check Server ──────────────────────────────────────────────────
	healthSrv := health.New("media-service", cfg.HealthPort, log)
	if store != nil {
		healthSrv.AddCheck("minio", func(ctx context.Context) error {
			return store.Ping(ctx)
		})
	}
	if tgStore.IsConfigured() {
		healthSrv.AddCheck("telegram", func(ctx context.Context) error {
			return tgStore.Ping(ctx)
		})
	}
	if dbStore != nil && dbStore.IsConfigured() {
		healthSrv.AddCheck("postgres", func(ctx context.Context) error {
			return dbStore.Ping(ctx)
		})
	}
	healthSrv.Start()
	defer healthSrv.Stop()

	// ── gRPC Server ───────────────────────────────────────────────────────────
	grpcServer := grpc.NewServer(
		grpc.KeepaliveParams(keepalive.ServerParameters{
			MaxConnectionIdle: 5 * time.Minute,
			Time:              2 * time.Minute,
			Timeout:           20 * time.Second,
		}),
		grpc.MaxRecvMsgSize(110*1024*1024), // 110 MB (slightly above our 100 MB file cap)
		grpc.MaxSendMsgSize(110*1024*1024),
		grpc.ChainUnaryInterceptor(
			func(ctx context.Context, req interface{}, info *grpc.UnaryServerInfo, handler grpc.UnaryHandler) (interface{}, error) {
				start := time.Now()
				resp, err := handler(ctx, req)
				log.Info("grpc call", zap.String("method", info.FullMethod), zap.Duration("d", time.Since(start)), zap.Error(err))
				return resp, err
			},
		),
	)

	mediapb.RegisterMediaServiceServer(grpcServer, server.New(store, tgStore, dbStore, localStore, log))
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
