package ratelimit

import (
	"context"
	"fmt"
	"net/http"
	"strconv"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/redis/go-redis/v9"
)

type Config struct {
	Prefix             string
	Limit              int64
	Window             time.Duration
	UseUserIfAvailable bool
}

// SlidingWindowLimiter provides a high-performance Redis sliding window rate limiter.
func SlidingWindowLimiter(rdb *redis.Client, cfg Config) gin.HandlerFunc {
	return func(c *gin.Context) {
		if rdb == nil {
			c.Next()
			return
		}

		keyPart := c.ClientIP()
		if cfg.UseUserIfAvailable {
			if uid := c.GetString("user_id"); uid != "" {
				keyPart = uid
			}
		}

		key := fmt.Sprintf("ratelimit:%s:%s", cfg.Prefix, keyPart)
		now := time.Now().UnixNano()
		windowStart := now - cfg.Window.Nanoseconds()

		ctx, cancel := context.WithTimeout(c.Request.Context(), 800*time.Millisecond)
		defer cancel()

		pipe := rdb.Pipeline()
		pipe.ZRemRangeByScore(ctx, key, "0", strconv.FormatInt(windowStart, 10))
		pipe.ZAdd(ctx, key, redis.Z{Score: float64(now), Member: strconv.FormatInt(now, 10)})
		cardCmd := pipe.ZCard(ctx, key)
		pipe.Expire(ctx, key, cfg.Window)

		_, err := pipe.Exec(ctx)
		if err != nil {
			// Fail open on Redis error
			c.Next()
			return
		}

		count := cardCmd.Val()
		remaining := cfg.Limit - count
		if remaining < 0 {
			remaining = 0
		}

		resetTime := time.Now().Add(cfg.Window).Unix()

		c.Header("X-RateLimit-Limit", strconv.FormatInt(cfg.Limit, 10))
		c.Header("X-RateLimit-Remaining", strconv.FormatInt(remaining, 10))
		c.Header("X-RateLimit-Reset", strconv.FormatInt(resetTime, 10))

		if count > cfg.Limit {
			c.Header("Retry-After", strconv.FormatInt(int64(cfg.Window.Seconds()), 10))
			c.JSON(http.StatusTooManyRequests, gin.H{
				"error":       "Rate limit exceeded. Please slow down.",
				"retry_after": int64(cfg.Window.Seconds()),
			})
			c.Abort()
			return
		}

		c.Next()
	}
}
