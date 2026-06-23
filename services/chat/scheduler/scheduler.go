package scheduler

import (
	"context"
	"time"

	"github.com/redis/go-redis/v9"
	"github.com/robfig/cron/v3"
	"go.uber.org/zap"

	"gochat/services/chat/repository"
)

// Scheduler polls for due scheduled messages and expired (self-destruct) messages,
// fires them, and publishes events to Redis pub/sub for the gateway to fan-out.
type Scheduler struct {
	msgRepo  *repository.MessageRepository
	redis    *redis.Client
	cron     *cron.Cron
	log      *zap.Logger
}

// New creates a Scheduler.
func New(msgRepo *repository.MessageRepository, redisClient *redis.Client, log *zap.Logger) *Scheduler {
	return &Scheduler{
		msgRepo: msgRepo,
		redis:   redisClient,
		cron:    cron.New(cron.WithSeconds()),
		log:     log,
	}
}

// Start registers cron jobs and starts the scheduler.
func (s *Scheduler) Start() {
	// Poll for scheduled messages every 10 seconds
	if _, err := s.cron.AddFunc("*/10 * * * * *", s.deliverScheduledMessages); err != nil {
		s.log.Fatal("failed to add scheduled-message cron", zap.Error(err))
	}

	// Poll for self-destructing messages every 30 seconds
	if _, err := s.cron.AddFunc("*/30 * * * * *", s.deleteExpiredMessages); err != nil {
		s.log.Fatal("failed to add expiry-message cron", zap.Error(err))
	}

	s.cron.Start()
	s.log.Info("message scheduler started")
}

// Stop gracefully stops the scheduler.
func (s *Scheduler) Stop() {
	s.cron.Stop()
}

// ── Cron jobs ─────────────────────────────────────────────────────────────────

func (s *Scheduler) deliverScheduledMessages() {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	msgs, err := s.msgRepo.GetDueScheduledMessages(ctx)
	if err != nil {
		s.log.Error("scheduler: fetching due messages", zap.Error(err))
		return
	}

	for _, msg := range msgs {
		if err := s.msgRepo.MarkAsDelivered(ctx, msg.ID); err != nil {
			s.log.Error("scheduler: marking message delivered",
				zap.String("msg_id", msg.ID.String()),
				zap.Error(err),
			)
			continue
		}

		// Publish to Redis channel so the gateway pushes it to connected clients
		channel := "chat:" + msg.ConversationID.String()
		payload := buildEventPayload("new_message", msg.ID.String(), msg.ConversationID.String(), msg.SenderID.String())
		if err := s.redis.Publish(ctx, channel, payload).Err(); err != nil {
			s.log.Warn("scheduler: redis publish failed",
				zap.String("channel", channel),
				zap.Error(err),
			)
		}

		s.log.Info("scheduled message delivered",
			zap.String("msg_id", msg.ID.String()),
			zap.String("conv_id", msg.ConversationID.String()),
		)
	}
}

func (s *Scheduler) deleteExpiredMessages() {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	msgs, err := s.msgRepo.GetExpiredMessages(ctx)
	if err != nil {
		s.log.Error("scheduler: fetching expired messages", zap.Error(err))
		return
	}

	for _, msg := range msgs {
		if err := s.msgRepo.Delete(ctx, msg.ID, msg.SenderID); err != nil {
			s.log.Error("scheduler: deleting expired message",
				zap.String("msg_id", msg.ID.String()),
				zap.Error(err),
			)
			continue
		}

		// Notify clients of deletion
		channel := "chat:" + msg.ConversationID.String()
		payload := buildEventPayload("message_deleted", msg.ID.String(), msg.ConversationID.String(), "system")
		_ = s.redis.Publish(ctx, channel, payload).Err()

		s.log.Info("self-destruct message deleted",
			zap.String("msg_id", msg.ID.String()),
		)
	}
}

// buildEventPayload creates a simple JSON string for Redis pub/sub.
// The gateway parses this and fans out to connected WebSocket clients.
func buildEventPayload(eventType, msgID, convID, actorID string) string {
	return `{"event":"` + eventType + `","msg_id":"` + msgID +
		`","conv_id":"` + convID + `","actor_id":"` + actorID + `"}`
}
