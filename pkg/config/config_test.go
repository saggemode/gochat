package config

import (
	"os"
	"testing"
)

func TestLoad_Defaults(t *testing.T) {
	os.Clearenv()

	cfg := Load()

	if cfg.RedisAddr != "localhost:6379" {
		t.Errorf("RedisAddr = %q, want %q", cfg.RedisAddr, "localhost:6379")
	}
	if cfg.RedisDB != 0 {
		t.Errorf("RedisDB = %d, want 0", cfg.RedisDB)
	}
	if cfg.GRPCPort != "50051" {
		t.Errorf("GRPCPort = %q, want %q", cfg.GRPCPort, "50051")
	}
	if cfg.HTTPPort != "8080" {
		t.Errorf("HTTPPort = %q, want %q", cfg.HTTPPort, "8080")
	}
	if cfg.MinIOEndpoint != "localhost:9000" {
		t.Errorf("MinIOEndpoint = %q, want %q", cfg.MinIOEndpoint, "localhost:9000")
	}
	if cfg.MinioBucket != "gochat-media" {
		t.Errorf("MinioBucket = %q, want %q", cfg.MinioBucket, "gochat-media")
	}
	if cfg.MinIOUseSSL != false {
		t.Error("MinIOUseSSL should default to false")
	}
}

func TestLoad_EnvironmentOverride(t *testing.T) {
	os.Clearenv()
	os.Setenv("REDIS_ADDR", "redis:6379")
	os.Setenv("GRPC_PORT", "50052")
	os.Setenv("HTTP_PORT", "9090")
	os.Setenv("MINIO_USE_SSL", "true")
	os.Setenv("REDIS_DB", "3")

	cfg := Load()

	if cfg.RedisAddr != "redis:6379" {
		t.Errorf("RedisAddr = %q, want %q", cfg.RedisAddr, "redis:6379")
	}
	if cfg.GRPCPort != "50052" {
		t.Errorf("GRPCPort = %q, want %q", cfg.GRPCPort, "50052")
	}
	if cfg.HTTPPort != "9090" {
		t.Errorf("HTTPPort = %q, want %q", cfg.HTTPPort, "9090")
	}
	if cfg.MinIOUseSSL != true {
		t.Error("MinIOUseSSL should be true")
	}
	if cfg.RedisDB != 3 {
		t.Errorf("RedisDB = %d, want 3", cfg.RedisDB)
	}
}

func TestLoad_JWTConfig(t *testing.T) {
	os.Clearenv()
	os.Setenv("JWT_EXPIRY_HOURS", "12")
	os.Setenv("JWT_REFRESH_EXPIRY_DAYS", "7")

	cfg := Load()

	if cfg.JWTExpiryDuration.Hours() != 12 {
		t.Errorf("JWTExpiryDuration = %v, want 12h", cfg.JWTExpiryDuration)
	}
	expectedRefresh := 7 * 24
	if cfg.JWTRefreshExpiry.Hours() != float64(expectedRefresh) {
		t.Errorf("JWTRefreshExpiry = %v, want %dh", cfg.JWTRefreshExpiry, expectedRefresh)
	}
}

func TestLoad_GRPCAddresses(t *testing.T) {
	os.Clearenv()
	os.Setenv("AUTH_GRPC_ADDR", "auth:50051")
	os.Setenv("CHAT_GRPC_ADDR", "chat:50052")

	cfg := Load()

	if cfg.AuthGRPCAddr != "auth:50051" {
		t.Errorf("AuthGRPCAddr = %q, want %q", cfg.AuthGRPCAddr, "auth:50051")
	}
	if cfg.ChatGRPCAddr != "chat:50052" {
		t.Errorf("ChatGRPCAddr = %q, want %q", cfg.ChatGRPCAddr, "chat:50052")
	}
}

func TestGetEnv(t *testing.T) {
	os.Clearenv()

	result := getEnv("NONEXISTENT_KEY", "fallback")
	if result != "fallback" {
		t.Errorf("getEnv(missing) = %q, want %q", result, "fallback")
	}

	os.Setenv("EXISTING_KEY", "value")
	result = getEnv("EXISTING_KEY", "fallback")
	if result != "value" {
		t.Errorf("getEnv(existing) = %q, want %q", result, "value")
	}
}

func TestGetEnvInt(t *testing.T) {
	os.Clearenv()

	result := getEnvInt("NONEXISTENT_KEY", 42)
	if result != 42 {
		t.Errorf("getEnvInt(missing) = %d, want 42", result)
	}

	os.Setenv("MY_INT", "100")
	result = getEnvInt("MY_INT", 0)
	if result != 100 {
		t.Errorf("getEnvInt(existing) = %d, want 100", result)
	}

	os.Setenv("BAD_INT", "abc")
	result = getEnvInt("BAD_INT", 7)
	if result != 7 {
		t.Errorf("getEnvInt(bad) = %d, want 7 (fallback)", result)
	}
}

func TestGetEnvBool(t *testing.T) {
	os.Clearenv()

	result := getEnvBool("NONEXISTENT_KEY", false)
	if result != false {
		t.Error("getEnvBool(missing) should return fallback false")
	}

	os.Setenv("MY_BOOL", "true")
	result = getEnvBool("MY_BOOL", false)
	if result != true {
		t.Error("getEnvBool(true) should return true")
	}

	os.Setenv("BAD_BOOL", "notabool")
	result = getEnvBool("BAD_BOOL", true)
	if result != true {
		t.Error("getEnvBool(bad) should return fallback true")
	}
}
