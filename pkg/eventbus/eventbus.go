package eventbus

import (
	"context"
	"encoding/json"
	"fmt"
	"time"

	"github.com/redis/go-redis/v9"
	"go.uber.org/zap"
)

type Event struct {
	Type      string                 `json:"type"`
	Payload   map[string]interface{} `json:"payload"`
	CreatedAt time.Time              `json:"created_at"`
}

type EventBus struct {
	rdb *redis.Client
	log *zap.Logger
}

func New(rdb *redis.Client, log *zap.Logger) *EventBus {
	return &EventBus{rdb: rdb, log: log}
}

func (eb *EventBus) Publish(ctx context.Context, topic string, eventType string, payload map[string]interface{}) error {
	if eb == nil || eb.rdb == nil {
		return nil
	}

	evt := Event{
		Type:      eventType,
		Payload:   payload,
		CreatedAt: time.Now(),
	}

	bytes, err := json.Marshal(evt)
	if err != nil {
		return fmt.Errorf("marshal event error: %w", err)
	}

	return eb.rdb.Publish(ctx, "events:"+topic, string(bytes)).Err()
}

func (eb *EventBus) Subscribe(ctx context.Context, topic string, handler func(evt Event)) {
	if eb == nil || eb.rdb == nil {
		return
	}

	sub := eb.rdb.Subscribe(ctx, "events:"+topic)
	ch := sub.Channel()

	go func() {
		for msg := range ch {
			var evt Event
			if err := json.Unmarshal([]byte(msg.Payload), &evt); err != nil {
				eb.log.Warn("unmarshal event error", zap.Error(err))
				continue
			}
			handler(evt)
		}
	}()
}
