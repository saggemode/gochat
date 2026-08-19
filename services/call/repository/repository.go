package repository

import (
	"context"
	"errors"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

type CallLog struct {
	ID                uuid.UUID
	CallerID          uuid.UUID
	CallerName        string
	CallerAvatarURL   string
	ReceiverID        uuid.UUID
	ReceiverName      string
	ReceiverAvatarURL string
	Type              string // "voice", "video"
	Status            string // "dialing", "active", "rejected", "missed", "ended", "busy"
	StartTime         time.Time
	EndTime           *time.Time
	DurationSec       int
}

type CallRepository struct {
	db *pgxpool.Pool
}

func New(db *pgxpool.Pool) *CallRepository {
	return &CallRepository{db: db}
}

func (r *CallRepository) Create(ctx context.Context, c *CallLog) error {
	if c.ID == uuid.Nil {
		c.ID = uuid.New()
	}
	if c.StartTime.IsZero() {
		c.StartTime = time.Now()
	}

	_, err := r.db.Exec(ctx, `
		INSERT INTO calls (id, caller_id, receiver_id, type, status, start_time, duration_sec)
		VALUES ($1, $2, $3, $4, $5, $6, $7)
	`, c.ID, c.CallerID, c.ReceiverID, c.Type, c.Status, c.StartTime, c.DurationSec)
	return err
}

func (r *CallRepository) GetByID(ctx context.Context, id uuid.UUID) (*CallLog, error) {
	c := &CallLog{}
	err := r.db.QueryRow(ctx, `
		SELECT c.id, c.caller_id, u1.display_name, u1.avatar_url,
		       c.receiver_id, u2.display_name, u2.avatar_url,
		       c.type, c.status, c.start_time, c.end_time, c.duration_sec
		FROM calls c
		JOIN users u1 ON c.caller_id = u1.id
		JOIN users u2 ON c.receiver_id = u2.id
		WHERE c.id = $1
	`, id).Scan(
		&c.ID, &c.CallerID, &c.CallerName, &c.CallerAvatarURL,
		&c.ReceiverID, &c.ReceiverName, &c.ReceiverAvatarURL,
		&c.Type, &c.Status, &c.StartTime, &c.EndTime, &c.DurationSec,
	)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, errors.New("call not found")
		}
		return nil, err
	}
	return c, nil
}

func (r *CallRepository) UpdateStatus(ctx context.Context, id uuid.UUID, status string) error {
	_, err := r.db.Exec(ctx, `
		UPDATE calls SET status = $1 WHERE id = $2
	`, status, id)
	return err
}

func (r *CallRepository) EndCall(ctx context.Context, id uuid.UUID, endTime time.Time, durationSec int) error {
	_, err := r.db.Exec(ctx, `
		UPDATE calls SET status = 'ended', end_time = $1, duration_sec = $2 WHERE id = $3
	`, endTime, durationSec, id)
	return err
}

func (r *CallRepository) GetHistory(ctx context.Context, userID uuid.UUID, offset, limit int) ([]*CallLog, int, error) {
	// Get total count
	var total int
	err := r.db.QueryRow(ctx, `
		SELECT COUNT(*) FROM calls WHERE caller_id = $1 OR receiver_id = $1
	`, userID).Scan(&total)
	if err != nil {
		return nil, 0, err
	}

	rows, err := r.db.Query(ctx, `
		SELECT c.id, c.caller_id, u1.display_name, u1.avatar_url,
		       c.receiver_id, u2.display_name, u2.avatar_url,
		       c.type, c.status, c.start_time, c.end_time, c.duration_sec
		FROM calls c
		JOIN users u1 ON c.caller_id = u1.id
		JOIN users u2 ON c.receiver_id = u2.id
		WHERE c.caller_id = $1 OR c.receiver_id = $1
		ORDER BY c.start_time DESC
		LIMIT $2 OFFSET $3
	`, userID, limit, offset)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()

	var history []*CallLog
	for rows.Next() {
		c := &CallLog{}
		err := rows.Scan(
			&c.ID, &c.CallerID, &c.CallerName, &c.CallerAvatarURL,
			&c.ReceiverID, &c.ReceiverName, &c.ReceiverAvatarURL,
			&c.Type, &c.Status, &c.StartTime, &c.EndTime, &c.DurationSec,
		)
		if err != nil {
			return nil, 0, err
		}
		history = append(history, c)
	}

	return history, total, nil
}
