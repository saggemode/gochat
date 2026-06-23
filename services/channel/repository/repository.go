package repository

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

type Channel struct {
	ID               uuid.UUID
	Name             string
	Description      string
	CreatedBy        uuid.UUID
	CreatedAt        time.Time
	SubscribersCount int
}

type ChannelMessage struct {
	ID        uuid.UUID
	ChannelID uuid.UUID
	SenderID  uuid.UUID
	Content   string
	Type      string
	MediaURL  string
	MediaMime string
	MediaSize int64
	CreatedAt time.Time
}

type ChannelRepository struct {
	db *pgxpool.Pool
}

func New(db *pgxpool.Pool) *ChannelRepository {
	return &ChannelRepository{db: db}
}

func (r *ChannelRepository) CreateChannel(ctx context.Context, name, description string, creatorID uuid.UUID) (*Channel, error) {
	c := &Channel{
		Name:             name,
		Description:      description,
		CreatedBy:        creatorID,
		SubscribersCount: 0,
	}
	err := r.db.QueryRow(ctx, `
		INSERT INTO channels (name, description, created_by)
		VALUES ($1, $2, $3)
		RETURNING id, created_at
	`, name, description, creatorID).Scan(&c.ID, &c.CreatedAt)
	if err != nil {
		return nil, err
	}

	// Auto-subscribe the creator
	_, err = r.db.Exec(ctx, `
		INSERT INTO channel_subscribers (channel_id, user_id)
		VALUES ($1, $2)
	`, c.ID, creatorID)
	if err != nil {
		// Log or handle error, but not fatal
	}
	c.SubscribersCount = 1

	return c, nil
}

func (r *ChannelRepository) DeleteChannel(ctx context.Context, id, ownerID uuid.UUID) error {
	res, err := r.db.Exec(ctx, `
		DELETE FROM channels WHERE id = $1 AND created_by = $2
	`, id, ownerID)
	if err != nil {
		return err
	}
	if res.RowsAffected() == 0 {
		return fmt.Errorf("channel not found or unauthorized")
	}
	return nil
}

func (r *ChannelRepository) SubscribeChannel(ctx context.Context, channelID, userID uuid.UUID) error {
	_, err := r.db.Exec(ctx, `
		INSERT INTO channel_subscribers (channel_id, user_id)
		VALUES ($1, $2)
		ON CONFLICT (channel_id, user_id) DO NOTHING
	`, channelID, userID)
	return err
}

func (r *ChannelRepository) UnsubscribeChannel(ctx context.Context, channelID, userID uuid.UUID) error {
	res, err := r.db.Exec(ctx, `
		DELETE FROM channel_subscribers WHERE channel_id = $1 AND user_id = $2
	`, channelID, userID)
	if err != nil {
		return err
	}
	if res.RowsAffected() == 0 {
		return fmt.Errorf("not subscribed to channel")
	}
	return nil
}

func (r *ChannelRepository) PublishChannelMessage(ctx context.Context, channelID, senderID uuid.UUID, content, msgType, mediaURL, mediaMime string, mediaSize int64) (*ChannelMessage, error) {
	// Verify that sender is the channel creator
	var creatorID uuid.UUID
	err := r.db.QueryRow(ctx, `SELECT created_by FROM channels WHERE id = $1`, channelID).Scan(&creatorID)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, fmt.Errorf("channel not found")
		}
		return nil, err
	}
	if creatorID != senderID {
		return nil, fmt.Errorf("only the channel creator can publish updates")
	}

	m := &ChannelMessage{
		ChannelID: channelID,
		SenderID:  senderID,
		Content:   content,
		Type:      msgType,
		MediaURL:  mediaURL,
		MediaMime: mediaMime,
		MediaSize: mediaSize,
	}

	err = r.db.QueryRow(ctx, `
		INSERT INTO channel_messages (channel_id, sender_id, content, type, media_url, media_mime, media_size)
		VALUES ($1, $2, $3, $4, $5, $6, $7)
		RETURNING id, created_at
	`, channelID, senderID, content, msgType, mediaURL, mediaMime, mediaSize).Scan(&m.ID, &m.CreatedAt)
	if err != nil {
		return nil, err
	}

	return m, nil
}

func (r *ChannelRepository) GetChannelMessages(ctx context.Context, channelID uuid.UUID, limit int, before time.Time) ([]*ChannelMessage, error) {
	var rows pgx.Rows
	var err error
	if before.IsZero() {
		rows, err = r.db.Query(ctx, `
			SELECT id, channel_id, sender_id, content, type, media_url, media_mime, media_size, created_at
			FROM channel_messages
			WHERE channel_id = $1
			ORDER BY created_at DESC
			LIMIT $2
		`, channelID, limit)
	} else {
		rows, err = r.db.Query(ctx, `
			SELECT id, channel_id, sender_id, content, type, media_url, media_mime, media_size, created_at
			FROM channel_messages
			WHERE channel_id = $1 AND created_at < $2
			ORDER BY created_at DESC
			LIMIT $3
		`, channelID, before, limit)
	}
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var messages []*ChannelMessage
	for rows.Next() {
		m := &ChannelMessage{}
		err := rows.Scan(&m.ID, &m.ChannelID, &m.SenderID, &m.Content, &m.Type, &m.MediaURL, &m.MediaMime, &m.MediaSize, &m.CreatedAt)
		if err != nil {
			return nil, err
		}
		messages = append(messages, m)
	}
	return messages, nil
}

func (r *ChannelRepository) GetChannelMetadata(ctx context.Context, channelID uuid.UUID) (*Channel, error) {
	c := &Channel{}
	err := r.db.QueryRow(ctx, `
		SELECT id, name, description, created_by, created_at,
		       (SELECT COUNT(*) FROM channel_subscribers WHERE channel_id = $1)
		FROM channels WHERE id = $1
	`, channelID).Scan(&c.ID, &c.Name, &c.Description, &c.CreatedBy, &c.CreatedAt, &c.SubscribersCount)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, fmt.Errorf("channel not found")
		}
		return nil, err
	}
	return c, nil
}

func (r *ChannelRepository) ListChannels(ctx context.Context, userID uuid.UUID) ([]*Channel, error) {
	var rows pgx.Rows
	var err error
	if userID == uuid.Nil {
		rows, err = r.db.Query(ctx, `
			SELECT c.id, c.name, c.description, c.created_by, c.created_at,
			       (SELECT COUNT(*) FROM channel_subscribers WHERE channel_id = c.id)
			FROM channels c
		`)
	} else {
		rows, err = r.db.Query(ctx, `
			SELECT c.id, c.name, c.description, c.created_by, c.created_at,
			       (SELECT COUNT(*) FROM channel_subscribers WHERE channel_id = c.id)
			FROM channels c
			JOIN channel_subscribers cs ON c.id = cs.channel_id
			WHERE cs.user_id = $1
		`, userID)
	}
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var channels []*Channel
	for rows.Next() {
		c := &Channel{}
		err := rows.Scan(&c.ID, &c.Name, &c.Description, &c.CreatedBy, &c.CreatedAt, &c.SubscribersCount)
		if err != nil {
			return nil, err
		}
		channels = append(channels, c)
	}
	return channels, nil
}
