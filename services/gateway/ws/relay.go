package ws

import (
	"context"
	"encoding/json"
	"sync"

	"github.com/redis/go-redis/v9"
	"go.uber.org/zap"
)

const (
	// Redis channel prefix for targeted user messages.
	userChannelPrefix = "ws:user:"

	// Redis channel for broadcast messages across all gateway instances.
	broadcastChannel = "ws:broadcast"
)

// redisMessage is the envelope published through Redis Pub/Sub.
type redisMessage struct {
	// Target user ID ("" for broadcast)
	UserID string `json:"user_id,omitempty"`
	// The user ID to exclude from broadcast delivery (typically the sender)
	ExcludeUserID string `json:"exclude_user_id,omitempty"`
	// The raw JSON payload to deliver to the client
	Payload string `json:"payload"`
}

// Relay handles cross-gateway WebSocket message delivery via Redis Pub/Sub.
// Each gateway instance subscribes to channels for its locally connected users,
// and publishes to channels when the target user is not found locally.
type Relay struct {
	rdb *redis.Client
	hub *Hub
	log *zap.Logger
	ctx context.Context

	// Tracks active per-user subscriptions so we don't double-subscribe.
	// Maps userID -> cancel function for the subscription goroutine.
	subs   map[string]context.CancelFunc
	subsMu sync.Mutex

	// Cancel function for the broadcast subscription.
	broadcastCancel context.CancelFunc
}

// NewRelay creates a new Redis Pub/Sub relay for cross-gateway WS delivery.
// Returns nil if rdb is nil (standalone / no-Redis mode).
func NewRelay(ctx context.Context, rdb *redis.Client, hub *Hub, log *zap.Logger) *Relay {
	if rdb == nil {
		return nil
	}
	r := &Relay{
		rdb:  rdb,
		hub:  hub,
		log:  log,
		ctx:  ctx,
		subs: make(map[string]context.CancelFunc),
	}
	r.subscribeBroadcast()
	return r
}

// subscribeBroadcast listens on the global broadcast channel and delivers
// messages to all local clients (excluding the original sender).
func (r *Relay) subscribeBroadcast() {
	subCtx, cancel := context.WithCancel(r.ctx)
	r.broadcastCancel = cancel

	sub := r.rdb.Subscribe(subCtx, broadcastChannel)
	go func() {
		defer sub.Close()
		ch := sub.Channel()
		for {
			select {
			case <-subCtx.Done():
				return
			case msg, ok := <-ch:
				if !ok {
					return
				}
				var rm redisMessage
				if err := json.Unmarshal([]byte(msg.Payload), &rm); err != nil {
					r.log.Warn("relay: failed to unmarshal broadcast message", zap.Error(err))
					continue
				}
				// Deliver to all local clients, excluding the sender
				r.hub.localBroadcast([]byte(rm.Payload), rm.ExcludeUserID)
			}
		}
	}()
}

// SubscribeUser starts listening on the Redis channel for a specific user.
// Called when a user's first local connection is registered.
// Safe to call multiple times for the same user — only the first call subscribes.
func (r *Relay) SubscribeUser(userID string) {
	if r == nil {
		return
	}

	r.subsMu.Lock()
	defer r.subsMu.Unlock()

	if _, exists := r.subs[userID]; exists {
		return // Already subscribed
	}

	subCtx, cancel := context.WithCancel(r.ctx)
	r.subs[userID] = cancel

	channel := userChannelPrefix + userID
	sub := r.rdb.Subscribe(subCtx, channel)

	go func() {
		defer sub.Close()
		ch := sub.Channel()
		for {
			select {
			case <-subCtx.Done():
				return
			case msg, ok := <-ch:
				if !ok {
					return
				}
				// Deliver the raw payload to all local connections for this user
				r.hub.localSendToUser(userID, []byte(msg.Payload))
			}
		}
	}()

	r.log.Debug("relay: subscribed to user channel", zap.String("user_id", userID))
}

// UnsubscribeUser stops listening on the Redis channel for a specific user.
// Called when a user's last local connection is unregistered.
func (r *Relay) UnsubscribeUser(userID string) {
	if r == nil {
		return
	}

	r.subsMu.Lock()
	defer r.subsMu.Unlock()

	if cancel, exists := r.subs[userID]; exists {
		cancel()
		delete(r.subs, userID)
		r.log.Debug("relay: unsubscribed from user channel", zap.String("user_id", userID))
	}
}

// PublishToUser publishes a message to a specific user's Redis channel
// so that whichever gateway instance they're connected to can deliver it.
func (r *Relay) PublishToUser(userID string, message []byte) {
	if r == nil {
		return
	}

	channel := userChannelPrefix + userID
	if err := r.rdb.Publish(r.ctx, channel, string(message)).Err(); err != nil {
		r.log.Error("relay: failed to publish to user channel",
			zap.String("user_id", userID),
			zap.Error(err),
		)
	}
}

// PublishBroadcast publishes a message to the global broadcast channel
// so all gateway instances can deliver it to their local clients.
func (r *Relay) PublishBroadcast(message []byte, excludeUserID string) {
	if r == nil {
		return
	}

	rm := redisMessage{
		ExcludeUserID: excludeUserID,
		Payload:       string(message),
	}
	data, err := json.Marshal(rm)
	if err != nil {
		r.log.Error("relay: failed to marshal broadcast message", zap.Error(err))
		return
	}

	if err := r.rdb.Publish(r.ctx, broadcastChannel, string(data)).Err(); err != nil {
		r.log.Error("relay: failed to publish broadcast", zap.Error(err))
	}
}

// Close shuts down all subscriptions.
func (r *Relay) Close() {
	if r == nil {
		return
	}

	if r.broadcastCancel != nil {
		r.broadcastCancel()
	}

	r.subsMu.Lock()
	defer r.subsMu.Unlock()
	for _, cancel := range r.subs {
		cancel()
	}
	r.subs = make(map[string]context.CancelFunc)
}
