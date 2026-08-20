package database

import (
	"context"
	"fmt"
	"time"

	"github.com/redis/go-redis/v9"
	"go.uber.org/zap"
)

// NewRedis creates and validates a Redis client.
// Retries with backoff to handle slow container startup.
func NewRedis(ctx context.Context, addr, password string, db int, log *zap.Logger) (*redis.Client, error) {
	var opts *redis.Options
	if len(addr) > 8 && (addr[:8] == "redis://" || addr[:9] == "rediss://") {
		var err error
		opts, err = redis.ParseURL(addr)
		if err != nil {
			return nil, fmt.Errorf("parsing redis URL: %w", err)
		}
	} else {
		opts = &redis.Options{
			Addr:         addr,
			Password:     password,
			DB:           db,
			DialTimeout:  5 * time.Second,
			ReadTimeout:  3 * time.Second,
			WriteTimeout: 3 * time.Second,
			PoolSize:     20,
			MinIdleConns: 5,
		}
	}
	client := redis.NewClient(opts)

	backoff := 100 * time.Millisecond
	for attempt := 1; attempt <= 2; attempt++ {
		if err := client.Ping(ctx).Err(); err == nil {
			log.Info("Redis connected", zap.Int("attempt", attempt), zap.String("addr", addr))
			return client, nil
		} else {
			log.Warn("Redis not ready, retrying...",
				zap.Int("attempt", attempt),
				zap.Duration("backoff", backoff),
				zap.String("addr", addr),
				zap.Error(err),
			)
		}
		if attempt < 2 {
			time.Sleep(backoff)
		}
	}

	return nil, fmt.Errorf("failed to connect to Redis at %q", addr)
}
