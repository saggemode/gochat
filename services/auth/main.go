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

	authpb "gochat/gen/auth"
	"gochat/pkg/config"
	"gochat/pkg/crypto"
	"gochat/pkg/database"
	"gochat/pkg/health"
	"gochat/pkg/jwtutil"
	"gochat/pkg/logger"
	"gochat/services/auth/repository"
	"gochat/services/auth/server"
)

func main() {
	log := logger.New("auth-service")
	defer log.Sync()

	cfg := config.Load()

	// ── Database ──────────────────────────────────────────────────────────────
	ctx := context.Background()

	db, err := database.NewPostgres(ctx, cfg.PostgresDSN, log)
	if err != nil {
		log.Fatal("failed to connect to postgres", zap.Error(err))
	}
	defer db.Close()

	if err := database.AutoMigrate(ctx, db, "auth", log); err != nil {
		log.Warn("boot auto-migration note", zap.Error(err))
	}

	// ── Redis ─────────────────────────────────────────────────────────────────
	redisClient, err := database.NewRedis(ctx, cfg.RedisAddr, cfg.RedisPassword, cfg.RedisDB, log)
	if err != nil {
		log.Fatal("failed to connect to redis", zap.Error(err))
	}
	defer redisClient.Close()

	// ── Health Check Server ──────────────────────────────────────────────────
	healthSrv := health.New("auth-service", cfg.HealthPort, log)
	healthSrv.AddCheck("postgres", func(ctx context.Context) error {
		return db.Ping(ctx)
	})
	healthSrv.AddCheck("redis", func(ctx context.Context) error {
		return redisClient.Ping(ctx).Err()
	})
	healthSrv.Start()
	defer healthSrv.Stop()

	// ── JWT Manager ───────────────────────────────────────────────────────────
	jwtMgr := jwtutil.NewManager(cfg.JWTSecret, cfg.JWTExpiryDuration, cfg.JWTRefreshExpiry)

	// ── E2EE Key Encryptor ─────────────────────────────────────────────────────
	var enc *crypto.Encryptor
	if cfg.E2EEKeyEncryptionKey != "" {
		enc, err = crypto.NewEncryptor(cfg.E2EEKeyEncryptionKey)
		if err != nil {
			log.Fatal("failed to initialize E2EE key encryptor", zap.Error(err))
		}
		log.Info("E2EE key encryption-at-rest enabled")
	} else {
		log.Warn("E2EE_KEY_ENCRYPTION_KEY not set — prekeys stored in plaintext (dev only)")
	}

	// ── Repository ────────────────────────────────────────────────────────────
	repo := repository.NewUserRepositoryWithEncryptor(db, enc)

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

	authpb.RegisterAuthServiceServer(grpcServer, server.New(repo, jwtMgr, redisClient, log))
	reflection.Register(grpcServer) // enables grpcurl for debugging

	// ── Token cleanup goroutine ───────────────────────────────────────────────
	go func() {
		ticker := time.NewTicker(1 * time.Hour)
		defer ticker.Stop()
		for range ticker.C {
			n, err := repo.CleanExpiredTokens(context.Background())
			if err != nil {
				log.Warn("failed to clean expired tokens", zap.Error(err))
			} else if n > 0 {
				log.Info("cleaned expired refresh tokens", zap.Int64("count", n))
			}
		}
	}()

	// ── Listen ────────────────────────────────────────────────────────────────
	addr := fmt.Sprintf(":%s", cfg.GRPCPort)
	lis, err := net.Listen("tcp", addr)
	if err != nil {
		log.Fatal("failed to listen", zap.Error(err))
	}

	// ── Graceful shutdown ─────────────────────────────────────────────────────
	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)

	go func() {
		log.Info("auth service started", zap.String("addr", addr))
		if err := grpcServer.Serve(lis); err != nil {
			log.Fatal("gRPC server error", zap.Error(err))
		}
	}()

	<-quit
	log.Info("shutting down auth service...")
	grpcServer.GracefulStop()
	log.Info("auth service stopped")
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
