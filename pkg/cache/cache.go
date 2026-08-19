package cache

import (
	"context"
	"encoding/json"
	"fmt"
	"time"

	"github.com/redis/go-redis/v9"
	"go.uber.org/zap"
)

type Cache struct {
	client *redis.Client
	log    *zap.Logger
}

func New(client *redis.Client, log *zap.Logger) *Cache {
	return &Cache{client: client, log: log}
}

func (c *Cache) Get(ctx context.Context, key string, dest interface{}) bool {
	if c == nil || c.client == nil {
		return false
	}

	val, err := c.client.Get(ctx, key).Result()
	if err != nil {
		return false
	}

	if err := json.Unmarshal([]byte(val), dest); err != nil {
		c.log.Warn("cache unmarshal error", zap.String("key", key), zap.Error(err))
		return false
	}

	return true
}

func (c *Cache) Set(ctx context.Context, key string, value interface{}, ttl time.Duration) error {
	if c == nil || c.client == nil {
		return nil
	}

	bytes, err := json.Marshal(value)
	if err != nil {
		return fmt.Errorf("cache marshal error: %w", err)
	}

	return c.client.Set(ctx, key, string(bytes), ttl).Err()
}

func (c *Cache) Delete(ctx context.Context, keys ...string) error {
	if c == nil || c.client == nil || len(keys) == 0 {
		return nil
	}
	return c.client.Del(ctx, keys...).Err()
}

func (c *Cache) InvalidatePattern(ctx context.Context, pattern string) error {
	if c == nil || c.client == nil {
		return nil
	}

	iter := c.client.Scan(ctx, 0, pattern, 0).Iterator()
	var keys []string
	for iter.Next(ctx) {
		keys = append(keys, iter.Val())
	}
	if err := iter.Err(); err != nil {
		return err
	}

	if len(keys) > 0 {
		return c.client.Del(ctx, keys...).Err()
	}
	return nil
}
