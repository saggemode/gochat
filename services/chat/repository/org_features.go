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

var (
	ErrFolderNotFound     = errors.New("folder not found")
	ErrLabelNotFound      = errors.New("label not found")
	ErrPollNotFound       = errors.New("poll not found")
	ErrPollOptionNotFound = errors.New("poll option not found")
	ErrPollClosed         = errors.New("poll is closed")
	ErrNotPollCreator     = errors.New("only the poll creator can close it")
	ErrInvalidPollOption  = errors.New("invalid poll option")
)

// ── Domain Models ─────────────────────────────────────────────────────────────

type Folder struct {
	ID        uuid.UUID
	UserID    uuid.UUID
	Name      string
	Icon      string
	Color     string
	SortOrder int
	CreatedAt time.Time
	UpdatedAt time.Time
	Items     []uuid.UUID // conversation IDs
}

type Label struct {
	ID        uuid.UUID
	UserID    uuid.UUID
	MessageID uuid.UUID
	Label     string
	Color     string
	CreatedAt time.Time
}

type ConversationAnalytics struct {
	UserID         uuid.UUID
	ConversationID uuid.UUID
	TotalMessages  int
	AvgResponseMs  int64
	LastActiveAt   *time.Time
	BusiestHour    int
	ComputedAt     time.Time
}

type NotificationProfile struct {
	UserID              uuid.UUID
	ConversationID      uuid.UUID
	Muted               bool
	MuteUntil           *time.Time
	Sound               string
	Vibration           bool
	Priority            string
	ShowPreview         bool
	NotifyOnMentionOnly bool
	CreatedAt           time.Time
	UpdatedAt           time.Time
}

type Poll struct {
	ID             uuid.UUID
	ConversationID uuid.UUID
	CreatedBy      uuid.UUID
	Question       string
	IsAnonymous    bool
	IsMultiple     bool
	IsClosed       bool
	ExpiresAt      *time.Time
	CreatedAt      time.Time
}

type PollOption struct {
	ID        uuid.UUID
	PollID    uuid.UUID
	Text      string
	SortOrder int
}

type PollVoteCount struct {
	OptionID uuid.UUID
	VoterIDs []uuid.UUID
	Count    int
}

// ── Folders ───────────────────────────────────────────────────────────────────

type FolderRepository struct {
	db *pgxpool.Pool
}

func NewFolderRepository(db *pgxpool.Pool) *FolderRepository {
	return &FolderRepository{db: db}
}

func (r *FolderRepository) Create(ctx context.Context, userID uuid.UUID, name, icon, color string, sortOrder int) (*Folder, error) {
	f := &Folder{}
	err := r.db.QueryRow(ctx, `
		INSERT INTO chat.chat_folders (user_id, name, icon, color, sort_order)
		VALUES ($1, $2, $3, $4, $5)
		RETURNING id, user_id, name, icon, color, sort_order, created_at, updated_at
	`, userID, name, icon, color, sortOrder).Scan(
		&f.ID, &f.UserID, &f.Name, &f.Icon, &f.Color, &f.SortOrder, &f.CreatedAt, &f.UpdatedAt,
	)
	if err != nil {
		return nil, fmt.Errorf("creating folder: %w", err)
	}
	return f, nil
}

func (r *FolderRepository) Get(ctx context.Context, userID uuid.UUID, folderID uuid.UUID) (*Folder, error) {
	f := &Folder{}
	err := r.db.QueryRow(ctx, `
		SELECT id, user_id, name, icon, color, sort_order, created_at, updated_at
		FROM chat.chat_folders WHERE id = $1 AND user_id = $2
	`, folderID, userID).Scan(
		&f.ID, &f.UserID, &f.Name, &f.Icon, &f.Color, &f.SortOrder, &f.CreatedAt, &f.UpdatedAt,
	)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, ErrFolderNotFound
	}
	if err != nil {
		return nil, err
	}
	if err := r.loadMembers(ctx, f); err != nil {
		return nil, err
	}
	return f, nil
}

func (r *FolderRepository) ListByUser(ctx context.Context, userID uuid.UUID) ([]*Folder, error) {
	rows, err := r.db.Query(ctx, `
		SELECT id, user_id, name, icon, color, sort_order, created_at, updated_at
		FROM chat.chat_folders WHERE user_id = $1
		ORDER BY sort_order ASC, created_at ASC
	`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var folders []*Folder
	for rows.Next() {
		f := &Folder{}
		if err := rows.Scan(
			&f.ID, &f.UserID, &f.Name, &f.Icon, &f.Color, &f.SortOrder, &f.CreatedAt, &f.UpdatedAt,
		); err != nil {
			return nil, err
		}
		folders = append(folders, f)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}

	for _, f := range folders {
		if err := r.loadMembers(ctx, f); err != nil {
			return nil, err
		}
	}
	return folders, nil
}

func (r *FolderRepository) Delete(ctx context.Context, userID, folderID uuid.UUID) error {
	tag, err := r.db.Exec(ctx, `
		DELETE FROM chat.chat_folders WHERE id = $1 AND user_id = $2
	`, folderID, userID)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrFolderNotFound
	}
	return nil
}

func (r *FolderRepository) AddConversation(ctx context.Context, userID, folderID, conversationID uuid.UUID) error {
	var owner uuid.UUID
	err := r.db.QueryRow(ctx, `SELECT user_id FROM chat.chat_folders WHERE id = $1`, folderID).Scan(&owner)
	if errors.Is(err, pgx.ErrNoRows) {
		return ErrFolderNotFound
	}
	if err != nil {
		return err
	}
	if owner != userID {
		return ErrFolderNotFound
	}

	_, err = r.db.Exec(ctx, `
		INSERT INTO chat.chat_folder_items (folder_id, conversation_id)
		VALUES ($1, $2)
		ON CONFLICT DO NOTHING
	`, folderID, conversationID)
	return err
}

func (r *FolderRepository) RemoveConversation(ctx context.Context, userID, folderID, conversationID uuid.UUID) error {
	var owner uuid.UUID
	err := r.db.QueryRow(ctx, `SELECT user_id FROM chat.chat_folders WHERE id = $1`, folderID).Scan(&owner)
	if errors.Is(err, pgx.ErrNoRows) {
		return ErrFolderNotFound
	}
	if err != nil {
		return err
	}
	if owner != userID {
		return ErrFolderNotFound
	}

	_, err = r.db.Exec(ctx, `
		DELETE FROM chat.chat_folder_items WHERE folder_id = $1 AND conversation_id = $2
	`, folderID, conversationID)
	return err
}

func (r *FolderRepository) loadMembers(ctx context.Context, f *Folder) error {
	rows, err := r.db.Query(ctx, `
		SELECT conversation_id FROM chat.chat_folder_items WHERE folder_id = $1
	`, f.ID)
	if err != nil {
		return err
	}
	defer rows.Close()

	f.Items = nil
	for rows.Next() {
		var id uuid.UUID
		if err := rows.Scan(&id); err != nil {
			return err
		}
		f.Items = append(f.Items, id)
	}
	return rows.Err()
}

// ── Labels ─────────────────────────────────────────────────────────────────────

type LabelRepository struct {
	db *pgxpool.Pool
}

func NewLabelRepository(db *pgxpool.Pool) *LabelRepository {
	return &LabelRepository{db: db}
}

func (r *LabelRepository) Add(ctx context.Context, userID, messageID uuid.UUID, label, color string) (*Label, error) {
	l := &Label{}
	err := r.db.QueryRow(ctx, `
		INSERT INTO chat.chat_labels (user_id, message_id, label, color)
		VALUES ($1, $2, $3, $4)
		ON CONFLICT (user_id, message_id, label) DO UPDATE SET color = EXCLUDED.color
		RETURNING id, user_id, message_id, label, color, created_at
	`, userID, messageID, label, color).Scan(
		&l.ID, &l.UserID, &l.MessageID, &l.Label, &l.Color, &l.CreatedAt,
	)
	if err != nil {
		return nil, fmt.Errorf("adding label: %w", err)
	}
	return l, nil
}

func (r *LabelRepository) Remove(ctx context.Context, userID, messageID uuid.UUID, label string) error {
	tag, err := r.db.Exec(ctx, `
		DELETE FROM chat.chat_labels
		WHERE user_id = $1 AND message_id = $2 AND label = $3
	`, userID, messageID, label)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrLabelNotFound
	}
	return nil
}

func (r *LabelRepository) List(ctx context.Context, userID uuid.UUID, labelFilter string) ([]*Label, error) {
	q := `SELECT id, user_id, message_id, label, color, created_at
	      FROM chat.chat_labels WHERE user_id = $1`
	args := []interface{}{userID}

	if labelFilter != "" {
		q += ` AND label = $2`
		args = append(args, labelFilter)
	}
	q += ` ORDER BY created_at DESC`

	rows, err := r.db.Query(ctx, q, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var labels []*Label
	for rows.Next() {
		l := &Label{}
		if err := rows.Scan(&l.ID, &l.UserID, &l.MessageID, &l.Label, &l.Color, &l.CreatedAt); err != nil {
			return nil, err
		}
		labels = append(labels, l)
	}
	return labels, rows.Err()
}

// ── Analytics ──────────────────────────────────────────────────────────────────

type AnalyticsRepository struct {
	db *pgxpool.Pool
}

func NewAnalyticsRepository(db *pgxpool.Pool) *AnalyticsRepository {
	return &AnalyticsRepository{db: db}
}

// Recompute calculates analytics for a single conversation and upserts the row.
func (r *AnalyticsRepository) Recompute(ctx context.Context, userID, conversationID uuid.UUID) error {
	tx, err := r.db.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)

	var totalMessages int
	if err := tx.QueryRow(ctx, `
		SELECT COUNT(*) FROM chat.messages
		WHERE conversation_id = $1 AND is_deleted = FALSE
	`, conversationID).Scan(&totalMessages); err != nil {
		return err
	}

	var avgResponseMs int64
	_ = tx.QueryRow(ctx, `
		WITH users_msgs AS (
			SELECT sender_id, created_at,
				LAG(created_at) OVER (ORDER BY created_at) AS prev_at,
				LAG(sender_id) OVER (ORDER BY created_at) AS prev_sender
			FROM chat.messages
			WHERE conversation_id = $1 AND is_deleted = FALSE
		)
		SELECT COALESCE(AVG(
			EXTRACT(EPOCH FROM (created_at - prev_at)) * 1000
		)::BIGINT, 0)::BIGINT
		FROM users_msgs
		WHERE prev_at IS NOT NULL AND sender_id <> prev_sender
	`, conversationID).Scan(&avgResponseMs)

	var busiestHour int
	_ = tx.QueryRow(ctx, `
		SELECT COALESCE(MOD(EXTRACT(HOUR FROM created_at)::INT, 24), 0)
		FROM chat.messages
		WHERE conversation_id = $1 AND is_deleted = FALSE
		GROUP BY EXTRACT(HOUR FROM created_at)
		ORDER BY COUNT(*) DESC
		LIMIT 1
	`, conversationID).Scan(&busiestHour)

	var lastActive *time.Time
	_ = tx.QueryRow(ctx, `
		SELECT MAX(created_at) FROM chat.messages
		WHERE conversation_id = $1 AND is_deleted = FALSE
	`, conversationID).Scan(&lastActive)

	_, err = tx.Exec(ctx, `
		INSERT INTO chat.chat_analytics (
			user_id, conversation_id, total_messages, avg_response_ms,
			last_active_at, busiest_hour, computed_at
		) VALUES ($1, $2, $3, $4, $5, $6, NOW())
		ON CONFLICT (user_id, conversation_id) DO UPDATE
		SET total_messages = EXCLUDED.total_messages,
		    avg_response_ms = EXCLUDED.avg_response_ms,
		    last_active_at = EXCLUDED.last_active_at,
		    busiest_hour = EXCLUDED.busiest_hour,
		    computed_at = NOW()
	`, userID, conversationID, totalMessages, avgResponseMs, lastActive, busiestHour)
	if err != nil {
		return err
	}
	return tx.Commit(ctx)
}

func (r *AnalyticsRepository) Get(ctx context.Context, userID uuid.UUID, conversationID *uuid.UUID) ([]*ConversationAnalytics, error) {
	q := `
		SELECT user_id, conversation_id, total_messages, avg_response_ms,
		       last_active_at, busiest_hour, computed_at
		FROM chat.chat_analytics WHERE user_id = $1
	`
	args := []interface{}{userID}
	if conversationID != nil {
		q += ` AND conversation_id = $2`
		args = append(args, *conversationID)
	}
	q += ` ORDER BY computed_at DESC`

	rows, err := r.db.Query(ctx, q, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var out []*ConversationAnalytics
	for rows.Next() {
		a := &ConversationAnalytics{}
		if err := rows.Scan(
			&a.UserID, &a.ConversationID, &a.TotalMessages, &a.AvgResponseMs,
			&a.LastActiveAt, &a.BusiestHour, &a.ComputedAt,
		); err != nil {
			return nil, err
		}
		out = append(out, a)
	}
	return out, rows.Err()
}

// ── Notification Profiles ────────────────────────────────────────────────────

type NotificationProfileRepository struct {
	db *pgxpool.Pool
}

func NewNotificationProfileRepository(db *pgxpool.Pool) *NotificationProfileRepository {
	return &NotificationProfileRepository{db: db}
}

func (r *NotificationProfileRepository) Set(ctx context.Context, p *NotificationProfile) error {
	var muteUntil *time.Time = p.MuteUntil

	_, err := r.db.Exec(ctx, `
		INSERT INTO chat.notification_profiles (
			user_id, conversation_id, muted, mute_until, sound,
			vibration, priority, show_preview, notify_on_mention_only,
			created_at, updated_at
		) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, NOW(), NOW())
		ON CONFLICT (user_id, conversation_id) DO UPDATE
		SET muted = EXCLUDED.muted,
		    mute_until = EXCLUDED.mute_until,
		    sound = EXCLUDED.sound,
		    vibration = EXCLUDED.vibration,
		    priority = EXCLUDED.priority,
		    show_preview = EXCLUDED.show_preview,
		    notify_on_mention_only = EXCLUDED.notify_on_mention_only,
		    updated_at = NOW()
	`, p.UserID, p.ConversationID, p.Muted, muteUntil, p.Sound,
		p.Vibration, p.Priority, p.ShowPreview, p.NotifyOnMentionOnly)
	return err
}

func (r *NotificationProfileRepository) ListByUser(ctx context.Context, userID uuid.UUID) ([]*NotificationProfile, error) {
	rows, err := r.db.Query(ctx, `
		SELECT user_id, conversation_id, muted, mute_until, sound,
		       vibration, priority, show_preview, notify_on_mention_only,
		       created_at, updated_at
		FROM chat.notification_profiles WHERE user_id = $1
		ORDER BY updated_at DESC
	`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var profiles []*NotificationProfile
	for rows.Next() {
		p := &NotificationProfile{}
		var mute *time.Time
		if err := rows.Scan(
			&p.UserID, &p.ConversationID, &p.Muted, &mute, &p.Sound,
			&p.Vibration, &p.Priority, &p.ShowPreview, &p.NotifyOnMentionOnly,
			&p.CreatedAt, &p.UpdatedAt,
		); err != nil {
			return nil, err
		}
		p.MuteUntil = mute
		profiles = append(profiles, p)
	}
	return profiles, rows.Err()
}

// ── Polls ─────────────────────────────────────────────────────────────────────

type PollRepository struct {
	db *pgxpool.Pool
}

func NewPollRepository(db *pgxpool.Pool) *PollRepository {
	return &PollRepository{db: db}
}

func (r *PollRepository) Create(ctx context.Context, p *Poll, options []string) (*Poll, error) {
	if len(options) < 2 {
		return nil, ErrInvalidPollOption
	}
	if len(options) > 10 {
		return nil, ErrInvalidPollOption
	}

	tx, err := r.db.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback(ctx)

	err = tx.QueryRow(ctx, `
		INSERT INTO chat.polls (
			conversation_id, created_by, question, is_anonymous,
			is_multiple, is_closed, expires_at
		) VALUES ($1, $2, $3, $4, $5, FALSE, $6)
		RETURNING id, conversation_id, created_by, question, is_anonymous,
		          is_multiple, is_closed, expires_at, created_at
	`, p.ConversationID, p.CreatedBy, p.Question, p.IsAnonymous, p.IsMultiple, p.ExpiresAt).Scan(
		&p.ID, &p.ConversationID, &p.CreatedBy, &p.Question, &p.IsAnonymous,
		&p.IsMultiple, &p.IsClosed, &p.ExpiresAt, &p.CreatedAt,
	)
	if err != nil {
		return nil, fmt.Errorf("inserting poll: %w", err)
	}

	for i, opt := range options {
		_, err := tx.Exec(ctx, `
			INSERT INTO chat.poll_options (poll_id, text, sort_order)
			VALUES ($1, $2, $3)
		`, p.ID, opt, i)
		if err != nil {
			return nil, fmt.Errorf("inserting option %d: %w", i, err)
		}
	}

	if err := tx.Commit(ctx); err != nil {
		return nil, err
	}
	return p, nil
}

func (r *PollRepository) Get(ctx context.Context, pollID uuid.UUID) (*Poll, error) {
	p := &Poll{}
	var expires *time.Time
	err := r.db.QueryRow(ctx, `
		SELECT id, conversation_id, created_by, question, is_anonymous,
		       is_multiple, is_closed, expires_at, created_at
		FROM chat.polls WHERE id = $1
	`, pollID).Scan(
		&p.ID, &p.ConversationID, &p.CreatedBy, &p.Question, &p.IsAnonymous,
		&p.IsMultiple, &p.IsClosed, &expires, &p.CreatedAt,
	)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, ErrPollNotFound
	}
	if err != nil {
		return nil, err
	}
	p.ExpiresAt = expires
	return p, nil
}

func (r *PollRepository) ListOptions(ctx context.Context, pollID uuid.UUID) ([]*PollOption, error) {
	rows, err := r.db.Query(ctx, `
		SELECT id, poll_id, text, sort_order
		FROM chat.poll_options WHERE poll_id = $1
		ORDER BY sort_order ASC
	`, pollID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var options []*PollOption
	for rows.Next() {
		o := &PollOption{}
		if err := rows.Scan(&o.ID, &o.PollID, &o.Text, &o.SortOrder); err != nil {
			return nil, err
		}
		options = append(options, o)
	}
	return options, rows.Err()
}

// GetVoteCounts returns vote counts per option for the given poll.
// If `requesterID` is non-nil, voter IDs are also returned except when the poll is anonymous.
func (r *PollRepository) GetVoteCounts(ctx context.Context, pollID uuid.UUID, requesterID *uuid.UUID, anonymous bool) (map[uuid.UUID]*PollVoteCount, error) {
	rows, err := r.db.Query(ctx, `
		SELECT option_id, user_id FROM chat.poll_votes WHERE poll_id = $1
	`, pollID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	counts := make(map[uuid.UUID]*PollVoteCount)
	for rows.Next() {
		var optID, userID uuid.UUID
		if err := rows.Scan(&optID, &userID); err != nil {
			return nil, err
		}
		c, ok := counts[optID]
		if !ok {
			c = &PollVoteCount{OptionID: optID}
			counts[optID] = c
		}
		if !anonymous {
			c.VoterIDs = append(c.VoterIDs, userID)
		}
		c.Count++
	}

	for _, c := range counts {
		c.VoterIDs = dedupeUUIDs(c.VoterIDs)
		c.Count = len(c.VoterIDs)
	}

	return counts, rows.Err()
}

func (r *PollRepository) Vote(ctx context.Context, pollID, userID uuid.UUID, optionIDs []uuid.UUID, isMultiple bool) error {
	if len(optionIDs) == 0 {
		return ErrInvalidPollOption
	}
	if !isMultiple && len(optionIDs) > 1 {
		return ErrInvalidPollOption
	}

	tx, err := r.db.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)

	var closed bool
	if err := tx.QueryRow(ctx, `SELECT is_closed FROM chat.polls WHERE id = $1 FOR UPDATE`, pollID).Scan(&closed); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return ErrPollNotFound
		}
		return err
	}
	if closed {
		return ErrPollClosed
	}

	if !isMultiple {
		if _, err := tx.Exec(ctx, `
			DELETE FROM chat.poll_votes WHERE poll_id = $1 AND user_id = $2
		`, pollID, userID); err != nil {
			return err
		}
	}

	for _, optID := range optionIDs {
		if _, err := tx.Exec(ctx, `
			INSERT INTO chat.poll_votes (poll_id, option_id, user_id)
			VALUES ($1, $2, $3)
			ON CONFLICT DO NOTHING
		`, pollID, optID, userID); err != nil {
			return err
		}
	}
	return tx.Commit(ctx)
}

func (r *PollRepository) Close(ctx context.Context, pollID, requesterID uuid.UUID) error {
	var createdBy uuid.UUID
	err := r.db.QueryRow(ctx, `SELECT created_by FROM chat.polls WHERE id = $1`, pollID).Scan(&createdBy)
	if errors.Is(err, pgx.ErrNoRows) {
		return ErrPollNotFound
	}
	if err != nil {
		return err
	}
	if createdBy != requesterID {
		return ErrNotPollCreator
	}

	_, err = r.db.Exec(ctx, `UPDATE chat.polls SET is_closed = TRUE WHERE id = $1`, pollID)
	return err
}

func dedupeUUIDs(ids []uuid.UUID) []uuid.UUID {
	seen := make(map[uuid.UUID]struct{}, len(ids))
	out := make([]uuid.UUID, 0, len(ids))
	for _, id := range ids {
		if _, ok := seen[id]; !ok {
			seen[id] = struct{}{}
			out = append(out, id)
		}
	}
	return out
}
