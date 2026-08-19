package middleware

import (
	"context"
	"fmt"
	"net/http"
	"strconv"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/redis/go-redis/v9"
)

type RateLimitConfig struct {
	Prefix             string
	Limit              int64
	Window             time.Duration
	UseUserIfAvailable bool
	TierLimits         map[string]int64 // e.g. {"guest": 20, "user": 120, "business": 600, "admin": 2000}
}

// Redis Lua script for precise Sliding-Window Log rate limiting
var slidingWindowScript = redis.NewScript(`
local key = KEYS[1]
local now = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
local limit = tonumber(ARGV[3])

local clearBefore = now - window

-- Remove outdated request timestamps
redis.call('ZREMRANGEBYSCORE', key, '-inf', clearBefore)

-- Count current requests in the sliding window
local currentRequests = redis.call('ZCARD', key)

if currentRequests < limit then
    redis.call('ZADD', key, now, now .. ':' .. math.random(100000, 999999))
    redis.call('EXPIRE', key, math.ceil(window / 1000))
    return {1, limit - currentRequests - 1, math.ceil((now + window) / 1000)}
else
    return {0, 0, math.ceil((now + window) / 1000)}
end
`)

func RateLimitMiddleware(rdb *redis.Client, cfg RateLimitConfig) gin.HandlerFunc {
	if rdb == nil {
		return func(c *gin.Context) {
			c.Next()
		}
	}
	return func(c *gin.Context) {
		keyPart := c.ClientIP()
		tier := "guest"

		if cfg.UseUserIfAvailable {
			if uid := c.GetString("user_id"); uid != "" {
				keyPart = uid
				tier = "user"
			}
			if role := c.GetString("user_role"); role != "" {
				tier = role
			}
		}

		// Determine rate limit for current tier
		limit := cfg.Limit
		if cfg.TierLimits != nil {
			if customLimit, exists := cfg.TierLimits[tier]; exists && customLimit > 0 {
				limit = customLimit
			}
		}

		key := fmt.Sprintf("%s:%s", cfg.Prefix, keyPart)
		nowMs := time.Now().UnixNano() / int64(time.Millisecond)
		windowMs := cfg.Window.Milliseconds()

		ctx, cancel := context.WithTimeout(c.Request.Context(), 800*time.Millisecond)
		defer cancel()

		res, err := slidingWindowScript.Run(ctx, rdb, []string{key}, nowMs, windowMs, limit).Result()
		if err != nil {
			// Fail-open strategy: log & allow request if Redis is temporarily unreachable
			c.Next()
			return
		}

		results, ok := res.([]interface{})
		if !ok || len(results) < 3 {
			c.Next()
			return
		}

		allowed := results[0].(int64) == 1
		remaining := results[1].(int64)
		resetUnixSec := results[2].(int64)

		c.Header("X-RateLimit-Limit", strconv.FormatInt(limit, 10))
		c.Header("X-RateLimit-Remaining", strconv.FormatInt(remaining, 10))
		c.Header("X-RateLimit-Reset", strconv.FormatInt(resetUnixSec, 10))

		if !allowed {
			retryAfter := int(cfg.Window.Seconds())
			c.Header("Retry-After", strconv.Itoa(retryAfter))
			c.JSON(http.StatusTooManyRequests, gin.H{
				"error":       "Too many requests. Please slow down.",
				"tier":        tier,
				"retry_after": retryAfter,
			})
			c.Abort()
			return
		}

		c.Next()
	}
}
