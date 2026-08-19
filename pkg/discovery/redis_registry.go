package discovery

import (
	"context"
	"encoding/json"
	"fmt"
	"time"

	"github.com/redis/go-redis/v9"
	"go.uber.org/zap"
)

const (
	defaultKeyPrefix = "discovery:services"
	defaultPubSub    = "discovery:events"
)

// RedisRegistry implements both Registry and Discovery using Redis.
type RedisRegistry struct {
	client *redis.Client
	log    *zap.Logger
}

// NewRedisRegistry creates a new RedisRegistry instance.
func NewRedisRegistry(client *redis.Client, log *zap.Logger) *RedisRegistry {
	return &RedisRegistry{
		client: client,
		log:    log.Named("discovery-redis"),
	}
}

func (r *RedisRegistry) instanceKey(serviceName, instanceID string) string {
	return fmt.Sprintf("%s:%s:%s", defaultKeyPrefix, serviceName, instanceID)
}

func (r *RedisRegistry) serviceKeyPattern(serviceName string) string {
	return fmt.Sprintf("%s:%s:*", defaultKeyPrefix, serviceName)
}

func (r *RedisRegistry) pubSubChannel(serviceName string) string {
	return fmt.Sprintf("%s:%s", defaultPubSub, serviceName)
}

// Register registers a service instance in Redis with a TTL.
func (r *RedisRegistry) Register(ctx context.Context, instance Instance, ttl time.Duration) error {
	instance.UpdatedAt = time.Now()
	data, err := json.Marshal(instance)
	if err != nil {
		return fmt.Errorf("marshal instance: %w", err)
	}

	key := r.instanceKey(instance.Name, instance.ID)
	if err := r.client.Set(ctx, key, data, ttl).Err(); err != nil {
		return fmt.Errorf("set instance in redis: %w", err)
	}

	// Notify watchers via pub/sub
	r.client.Publish(ctx, r.pubSubChannel(instance.Name), "register")

	r.log.Debug("registered service instance",
		zap.String("service", instance.Name),
		zap.String("instance_id", instance.ID),
		zap.String("addr", instance.Addr),
		zap.Duration("ttl", ttl),
	)
	return nil
}

// Deregister removes a service instance from Redis.
func (r *RedisRegistry) Deregister(ctx context.Context, serviceName, instanceID string) error {
	key := r.instanceKey(serviceName, instanceID)
	if err := r.client.Del(ctx, key).Err(); err != nil {
		return fmt.Errorf("delete instance from redis: %w", err)
	}

	// Notify watchers
	r.client.Publish(ctx, r.pubSubChannel(serviceName), "deregister")

	r.log.Debug("deregistered service instance",
		zap.String("service", serviceName),
		zap.String("instance_id", instanceID),
	)
	return nil
}

// Heartbeat refreshes the TTL of an instance in Redis.
func (r *RedisRegistry) Heartbeat(ctx context.Context, serviceName, instanceID string, ttl time.Duration) error {
	key := r.instanceKey(serviceName, instanceID)
	exists, err := r.client.Expire(ctx, key, ttl).Result()
	if err != nil {
		return fmt.Errorf("heartbeat expire: %w", err)
	}
	if !exists {
		return fmt.Errorf("instance %s/%s does not exist or expired", serviceName, instanceID)
	}
	return nil
}

// GetInstances returns all active instances of a service from Redis.
func (r *RedisRegistry) GetInstances(ctx context.Context, serviceName string) ([]Instance, error) {
	var cursor uint64
	var keys []string
	pattern := r.serviceKeyPattern(serviceName)

	for {
		var batch []string
		var err error
		batch, cursor, err = r.client.Scan(ctx, cursor, pattern, 100).Result()
		if err != nil {
			return nil, fmt.Errorf("scan redis keys: %w", err)
		}
		keys = append(keys, batch...)
		if cursor == 0 {
			break
		}
	}

	if len(keys) == 0 {
		return nil, nil
	}

	rawInstances, err := r.client.MGet(ctx, keys...).Result()
	if err != nil {
		return nil, fmt.Errorf("mget instances: %w", err)
	}

	var instances []Instance
	for _, raw := range rawInstances {
		if raw == nil {
			continue
		}
		var inst Instance
		str, ok := raw.(string)
		if !ok {
			continue
		}
		if err := json.Unmarshal([]byte(str), &inst); err == nil {
			instances = append(instances, inst)
		}
	}

	return instances, nil
}

// Watch returns a channel that yields updated instance lists when changes occur.
func (r *RedisRegistry) Watch(ctx context.Context, serviceName string) (<-chan []Instance, error) {
	ch := make(chan []Instance, 10)

	// Send initial instances immediately
	initial, err := r.GetInstances(ctx, serviceName)
	if err == nil {
		ch <- initial
	}

	pubsub := r.client.Subscribe(ctx, r.pubSubChannel(serviceName))

	go func() {
		defer pubsub.Close()
		defer close(ch)

		// Periodic poll fallback in case pubsub misses a key expiration
		ticker := time.NewTicker(10 * time.Second)
		defer ticker.Stop()

		msgChan := pubsub.Channel()

		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				instances, err := r.GetInstances(ctx, serviceName)
				if err == nil {
					ch <- instances
				}
			case _, ok := <-msgChan:
				if !ok {
					return
				}
				instances, err := r.GetInstances(ctx, serviceName)
				if err == nil {
					ch <- instances
				}
			}
		}
	}()

	return ch, nil
}
