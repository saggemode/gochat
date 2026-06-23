package config

import (
	"fmt"
	"os"
	"strconv"
	"time"
)

// Config holds all configuration loaded from environment variables.
// Each service embeds this struct and adds its own fields.
type Config struct {
	// Database
	PostgresDSN string

	// Redis
	RedisAddr     string
	RedisPassword string
	RedisDB       int

	// JWT (used by auth service and gateway)
	JWTSecret             string
	JWTExpiryDuration     time.Duration
	JWTRefreshExpiry      time.Duration

	// Service addresses (used by gateway)
	AuthGRPCAddr  string
	ChatGRPCAddr  string
	MediaGRPCAddr string
	AuthzGRPCAddr string
	GroupGRPCAddr string
	StoryGRPCAddr string
	CallGRPCAddr     string
	ChannelGRPCAddr  string
	AIGRPCAddr       string
	PaymentGRPCAddr  string
	SocialGRPCAddr   string
	MiniAppGRPCAddr  string
	BusinessGRPCAddr string

	// gRPC port (used by each service)
	GRPCPort string

	// HTTP port (used by gateway)
	HTTPPort string

	// MinIO (used by media service)
	MinIOEndpoint  string
	MinIOAccessKey string
	MinIOSecretKey string
	MinioBucket    string
	MinIOUseSSL    bool

	// CORS
	CORSOrigins string
}

// Load reads all config from environment variables with sensible defaults.
func Load() *Config {
	return &Config{
		PostgresDSN: getEnv("POSTGRES_DSN",
			"postgres://gochat:gochat_secret@localhost:5432/gochat?sslmode=disable"),

		RedisAddr:     getEnv("REDIS_ADDR", "localhost:6379"),
		RedisPassword: getEnv("REDIS_PASSWORD", "redis_secret"),
		RedisDB:       getEnvInt("REDIS_DB", 0),

		JWTSecret:         requireEnv("JWT_SECRET"),
		JWTExpiryDuration: time.Duration(getEnvInt("JWT_EXPIRY_HOURS", 24)) * time.Hour,
		JWTRefreshExpiry:  time.Duration(getEnvInt("JWT_REFRESH_EXPIRY_DAYS", 30)) * 24 * time.Hour,

		AuthGRPCAddr:    getEnv("AUTH_GRPC_ADDR", "localhost:50051"),
		ChatGRPCAddr:    getEnv("CHAT_GRPC_ADDR", "localhost:50052"),
		MediaGRPCAddr:   getEnv("MEDIA_GRPC_ADDR", "localhost:50053"),
		AuthzGRPCAddr:   getEnv("AUTHZ_GRPC_ADDR", "localhost:50054"),
		GroupGRPCAddr:   getEnv("GROUP_GRPC_ADDR", "localhost:50055"),
		StoryGRPCAddr:   getEnv("STORY_GRPC_ADDR", "localhost:50056"),
		CallGRPCAddr:    getEnv("CALL_GRPC_ADDR", "localhost:50057"),
		ChannelGRPCAddr: getEnv("CHANNEL_GRPC_ADDR", "localhost:50058"),
		AIGRPCAddr:       getEnv("AI_GRPC_ADDR", "localhost:50059"),
		PaymentGRPCAddr:  getEnv("PAYMENT_GRPC_ADDR", "localhost:50060"),
		SocialGRPCAddr:   getEnv("SOCIAL_GRPC_ADDR", "localhost:50061"),
		MiniAppGRPCAddr:  getEnv("MINIAPP_GRPC_ADDR", "localhost:50062"),
		BusinessGRPCAddr: getEnv("BUSINESS_GRPC_ADDR", "localhost:50063"),

		GRPCPort: getEnv("GRPC_PORT", "50051"),
		HTTPPort: getEnv("HTTP_PORT", "8080"),

		MinIOEndpoint:  getEnv("MINIO_ENDPOINT", "localhost:9000"),
		MinIOAccessKey: getEnv("MINIO_ACCESS_KEY", "minioadmin"),
		MinIOSecretKey: getEnv("MINIO_SECRET_KEY", "minioadmin_secret"),
		MinioBucket:    getEnv("MINIO_BUCKET", "gochat-media"),
		MinIOUseSSL:    getEnvBool("MINIO_USE_SSL", false),

		CORSOrigins: getEnv("CORS_ORIGINS", "http://localhost:5173"),
	}
}

func getEnv(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

func requireEnv(key string) string {
	v := os.Getenv(key)
	if v == "" {
		// For local dev, use a default — in prod this panics so it's caught at startup
		defaultVal := "dev_jwt_secret_change_in_prod"
		fmt.Printf("WARNING: %s not set, using default (UNSAFE for production)\n", key)
		return defaultVal
	}
	return v
}

func getEnvInt(key string, fallback int) int {
	if v := os.Getenv(key); v != "" {
		if i, err := strconv.Atoi(v); err == nil {
			return i
		}
	}
	return fallback
}

func getEnvBool(key string, fallback bool) bool {
	if v := os.Getenv(key); v != "" {
		b, err := strconv.ParseBool(v)
		if err == nil {
			return b
		}
	}
	return fallback
}
