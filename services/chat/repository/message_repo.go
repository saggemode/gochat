package repository

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// ── Domain Models ─────────────────────────────────────────────────────────────

type ConversationType string

const (
	ConversationDirect ConversationType = "direct"
	ConversationGroup  ConversationType = "group"
)

type MessageType string
type MessageStatus string

const (
	MessageTypeText  MessageType = "text"
	MessageTypeImage MessageType = "image"
	MessageTypeVideo MessageType = "video"
	MessageTypeAudio MessageType = "audio"
	MessageTypeVoice MessageType = "voice"
	MessageTypeFile  MessageType = "file"

	MessageStatusPending   MessageStatus = "pending"
	MessageStatusSent      MessageStatus = "sent"
	MessageStatusDelivered MessageStatus = "delivered"
	MessageStatusRead      MessageStatus = "read"
	MessageStatusFailed    MessageStatus = "failed"
	MessageStatusScheduled MessageStatus = "scheduled"
)

type Conversation struct {
	ID        uuid.UUID
	Type      ConversationType
	Name      string
	AvatarURL string
	CreatedBy uuid.UUID
	CreatedAt time.Time
	UpdatedAt time.Time
	Members   []uuid.UUID
}

type Message struct {
	ID                  uuid.UUID
	ConversationID      uuid.UUID
	SenderID            uuid.UUID
	Content             string
	Type                MessageType
	Status              MessageStatus
	MediaURL            string
	MediaMime           string
	MediaSize           int64
	ParentID            *uuid.UUID
	ThreadCount         int
	SendAt              time.Time
	ExpiresAt           *time.Time
	IsPinned            bool
	IsEdited            bool
	IsDeleted           bool
	Reactions           []Reaction
	Reads               []MessageRead
	ForwardedFromID     *uuid.UUID
	ForwardedFromConv   *uuid.UUID
	ForwardedFromSender *uuid.UUID
	MentionedUserIDs    []uuid.UUID
	CreatedAt           time.Time
	UpdatedAt           time.Time
}

type Reaction struct {
	MessageID uuid.UUID
	UserID    uuid.UUID
	Emoji     string
	CreatedAt time.Time
}

type MessageRead struct {
	MessageID uuid.UUID
	UserID    uuid.UUID
	ReadAt    time.Time
}

type EditHistoryEntry struct {
	Content  string
	EditedAt time.Time
}

var (
	ErrConversationNotFound = errors.New("conversation not found")
	ErrMessageNotFound      = errors.New("message not found")
	ErrNotMember            = errors.New("user is not a member of this conversation")
	ErrNotAuthor            = errors.New("user is not the author of this message")
)

// ── Conversation Repository ───────────────────────────────────────────────────

type ConversationRepository struct {
	db *pgxpool.Pool
}

func NewConversationRepository(db *pgxpool.Pool) *ConversationRepository {
	return &ConversationRepository{db: db}
}

func (r *ConversationRepository) FindDirectConversation(ctx context.Context, user1, user2 uuid.UUID) (*Conversation, error) {
	var convID uuid.UUID
	err := r.db.QueryRow(ctx, `
		SELECT cm1.conversation_id
		FROM conversation_members cm1
		JOIN conversation_members cm2 ON cm1.conversation_id = cm2.conversation_id
		JOIN conversations c ON c.id = cm1.conversation_id
		WHERE c.type = 'direct'
		  AND cm1.user_id = $1
		  AND cm2.user_id = $2
		LIMIT 1
	`, user1, user2).Scan(&convID)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return r.GetByID(ctx, convID)
}

func (r *ConversationRepository) Create(ctx context.Context, convType ConversationType, name string, creatorID uuid.UUID, memberIDs []uuid.UUID) (*Conversation, error) {
	allMembers := uniqueIDs(append(memberIDs, creatorID))

	// Check if a direct 1:1 conversation between these users already exists to prevent duplicates
	if convType == ConversationDirect && len(allMembers) == 2 {
		existing, err := r.FindDirectConversation(ctx, allMembers[0], allMembers[1])
		if err == nil && existing != nil {
			return existing, nil
		}
	}

	tx, err := r.db.Begin(ctx)
	if err != nil {
		return nil, fmt.Errorf("begin tx: %w", err)
	}
	defer tx.Rollback(ctx)

	conv := &Conversation{}
	err = tx.QueryRow(ctx, `
		INSERT INTO conversations (type, name, created_by)
		VALUES ($1, $2, $3)
		RETURNING id, type, name, avatar_url, created_by, created_at, updated_at
	`, string(convType), name, creatorID).Scan(
		&conv.ID, &conv.Type, &conv.Name, &conv.AvatarURL,
		&conv.CreatedBy, &conv.CreatedAt, &conv.UpdatedAt,
	)
	if err != nil {
		return nil, fmt.Errorf("insert conversation: %w", err)
	}

	for _, memberID := range allMembers {
		role := "member"
		if memberID == creatorID {
			role = "owner"
		}
		if _, err := tx.Exec(ctx, `
			INSERT INTO conversation_members (conversation_id, user_id, role)
			VALUES ($1, $2, $3)
		`, conv.ID, memberID, role); err != nil {
			return nil, fmt.Errorf("insert member %s: %w", memberID, err)
		}
	}

	if err := tx.Commit(ctx); err != nil {
		return nil, fmt.Errorf("commit: %w", err)
	}

	conv.Members = allMembers
	conv.Type = convType
	return conv, nil
}

func (r *ConversationRepository) GetByID(ctx context.Context, id uuid.UUID) (*Conversation, error) {
	conv := &Conversation{}
	err := r.db.QueryRow(ctx, `
		SELECT id, type, name, avatar_url, created_by, created_at, updated_at
		FROM conversations WHERE id = $1
	`, id).Scan(&conv.ID, &conv.Type, &conv.Name, &conv.AvatarURL,
		&conv.CreatedBy, &conv.CreatedAt, &conv.UpdatedAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, ErrConversationNotFound
	}
	if err != nil {
		return nil, err
	}

	members, err := r.getMembers(ctx, id)
	if err != nil {
		return nil, err
	}
	conv.Members = members
	return conv, nil
}

func (r *ConversationRepository) ListForUser(ctx context.Context, userID uuid.UUID, page, pageSize int) ([]*Conversation, int, error) {
	offset := (page - 1) * pageSize

	rows, err := r.db.Query(ctx, `
		SELECT c.id, c.type, c.name, c.avatar_url, c.created_by, c.created_at, c.updated_at
		FROM conversations c
		INNER JOIN conversation_members cm ON cm.conversation_id = c.id
		WHERE cm.user_id = $1
		ORDER BY c.updated_at DESC
		LIMIT $2 OFFSET $3
	`, userID, pageSize, offset)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()

	var convs []*Conversation
	for rows.Next() {
		c := &Conversation{}
		if err := rows.Scan(&c.ID, &c.Type, &c.Name, &c.AvatarURL,
			&c.CreatedBy, &c.CreatedAt, &c.UpdatedAt); err != nil {
			return nil, 0, err
		}
		if members, err := r.getMembers(ctx, c.ID); err == nil {
			c.Members = members
		}
		convs = append(convs, c)
	}

	var total int
	_ = r.db.QueryRow(ctx, `
		SELECT COUNT(*) FROM conversation_members WHERE user_id = $1
	`, userID).Scan(&total)

	return convs, total, rows.Err()
}

func (r *ConversationRepository) IsMember(ctx context.Context, convID, userID uuid.UUID) (bool, error) {
	var exists bool
	err := r.db.QueryRow(ctx, `
		SELECT EXISTS(
			SELECT 1 FROM conversation_members
			WHERE conversation_id = $1 AND user_id = $2
		)
	`, convID, userID).Scan(&exists)
	return exists, err
}

func (r *ConversationRepository) AddMember(ctx context.Context, convID, userID uuid.UUID) error {
	_, err := r.db.Exec(ctx, `
		INSERT INTO conversation_members (conversation_id, user_id)
		VALUES ($1, $2)
		ON CONFLICT DO NOTHING
	`, convID, userID)
	return err
}

func (r *ConversationRepository) RemoveMember(ctx context.Context, convID, userID uuid.UUID) error {
	_, err := r.db.Exec(ctx, `
		DELETE FROM conversation_members
		WHERE conversation_id = $1 AND user_id = $2
	`, convID, userID)
	return err
}

func (r *ConversationRepository) getMembers(ctx context.Context, convID uuid.UUID) ([]uuid.UUID, error) {
	rows, err := r.db.Query(ctx, `
		SELECT user_id FROM conversation_members WHERE conversation_id = $1
	`, convID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var ids []uuid.UUID
	for rows.Next() {
		var id uuid.UUID
		if err := rows.Scan(&id); err != nil {
			return nil, err
		}
		ids = append(ids, id)
	}
	return ids, rows.Err()
}

// ── Message Repository ────────────────────────────────────────────────────────

type MessageRepository struct {
	db *pgxpool.Pool
}

func NewMessageRepository(db *pgxpool.Pool) *MessageRepository {
	return &MessageRepository{db: db}
}

func (r *MessageRepository) Create(ctx context.Context, msg *Message) (*Message, error) {
	err := r.db.QueryRow(ctx, `
		INSERT INTO messages (
			conversation_id, sender_id, content, type, status,
			media_url, media_mime, media_size,
			parent_id, send_at, expires_at,
			forwarded_from_id, forwarded_from_conv, forwarded_from_sender
		) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14)
		RETURNING id, conversation_id, sender_id, content, type, status,
		          media_url, media_mime, media_size,
		          parent_id, thread_count, send_at, expires_at,
		          is_pinned, is_edited, is_deleted,
		          forwarded_from_id, forwarded_from_conv, forwarded_from_sender,
		          created_at, updated_at
	`, msg.ConversationID, msg.SenderID, msg.Content, string(msg.Type), string(msg.Status),
		msg.MediaURL, msg.MediaMime, msg.MediaSize,
		msg.ParentID, msg.SendAt, msg.ExpiresAt,
		msg.ForwardedFromID, msg.ForwardedFromConv, msg.ForwardedFromSender,
	).Scan(
		&msg.ID, &msg.ConversationID, &msg.SenderID, &msg.Content, &msg.Type, &msg.Status,
		&msg.MediaURL, &msg.MediaMime, &msg.MediaSize,
		&msg.ParentID, &msg.ThreadCount, &msg.SendAt, &msg.ExpiresAt,
		&msg.IsPinned, &msg.IsEdited, &msg.IsDeleted,
		&msg.ForwardedFromID, &msg.ForwardedFromConv, &msg.ForwardedFromSender,
		&msg.CreatedAt, &msg.UpdatedAt,
	)
	if err != nil {
		return nil, fmt.Errorf("inserting message: %w", err)
	}

	if len(msg.MentionedUserIDs) > 0 {
		if err := r.SaveMentions(ctx, msg.ID, msg.MentionedUserIDs); err != nil {
			return nil, fmt.Errorf("saving mentions: %w", err)
		}
	}

	return msg, nil
}

func (r *MessageRepository) GetByID(ctx context.Context, id uuid.UUID) (*Message, error) {
	msg := &Message{}
	err := r.db.QueryRow(ctx, `
		SELECT id, conversation_id, sender_id, content, type, status,
		       media_url, media_mime, media_size,
		       parent_id, thread_count, send_at, expires_at,
		       is_pinned, is_edited, is_deleted,
		       forwarded_from_id, forwarded_from_conv, forwarded_from_sender,
		       created_at, updated_at
		FROM messages WHERE id = $1
	`, id).Scan(
		&msg.ID, &msg.ConversationID, &msg.SenderID, &msg.Content, &msg.Type, &msg.Status,
		&msg.MediaURL, &msg.MediaMime, &msg.MediaSize,
		&msg.ParentID, &msg.ThreadCount, &msg.SendAt, &msg.ExpiresAt,
		&msg.IsPinned, &msg.IsEdited, &msg.IsDeleted,
		&msg.ForwardedFromID, &msg.ForwardedFromConv, &msg.ForwardedFromSender,
		&msg.CreatedAt, &msg.UpdatedAt,
	)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, ErrMessageNotFound
	}
	return msg, err
}

func (r *MessageRepository) GetLastMessage(ctx context.Context, convID uuid.UUID) (*Message, error) {
	msg := &Message{}
	err := r.db.QueryRow(ctx, `
		SELECT id, conversation_id, sender_id, content, type, status,
		       media_url, media_mime, media_size,
		       parent_id, thread_count, send_at, expires_at,
		       is_pinned, is_edited, is_deleted,
		       forwarded_from_id, forwarded_from_conv, forwarded_from_sender,
		       created_at, updated_at
		FROM messages
		WHERE conversation_id = $1 AND is_deleted = FALSE AND status <> 'scheduled'
		ORDER BY created_at DESC
		LIMIT 1
	`, convID).Scan(
		&msg.ID, &msg.ConversationID, &msg.SenderID, &msg.Content, &msg.Type, &msg.Status,
		&msg.MediaURL, &msg.MediaMime, &msg.MediaSize,
		&msg.ParentID, &msg.ThreadCount, &msg.SendAt, &msg.ExpiresAt,
		&msg.IsPinned, &msg.IsEdited, &msg.IsDeleted,
		&msg.ForwardedFromID, &msg.ForwardedFromConv, &msg.ForwardedFromSender,
		&msg.CreatedAt, &msg.UpdatedAt,
	)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, nil
	}
	return msg, err
}

func (r *MessageRepository) List(ctx context.Context, convID uuid.UUID, cursor *uuid.UUID, limit int) ([]*Message, string, bool, error) {
	var rows pgx.Rows
	var err error

	if cursor == nil {
		rows, err = r.db.Query(ctx, `
			SELECT id, conversation_id, sender_id, content, type, status,
			       media_url, media_mime, media_size,
			       parent_id, thread_count, send_at, expires_at,
			       is_pinned, is_edited, is_deleted,
			       forwarded_from_id, forwarded_from_conv, forwarded_from_sender,
			       created_at, updated_at
			FROM messages
			WHERE conversation_id = $1 AND is_deleted = FALSE AND status <> 'scheduled'
			ORDER BY created_at DESC
			LIMIT $2
		`, convID, limit+1)
	} else {
		rows, err = r.db.Query(ctx, `
			SELECT id, conversation_id, sender_id, content, type, status,
			       media_url, media_mime, media_size,
			       parent_id, thread_count, send_at, expires_at,
			       is_pinned, is_edited, is_deleted,
			       forwarded_from_id, forwarded_from_conv, forwarded_from_sender,
			       created_at, updated_at
			FROM messages
			WHERE conversation_id = $1 AND is_deleted = FALSE AND status <> 'scheduled'
			  AND created_at < (SELECT created_at FROM messages WHERE id = $2)
			ORDER BY created_at DESC
			LIMIT $3
		`, convID, *cursor, limit+1)
	}
	if err != nil {
		return nil, "", false, err
	}
	defer rows.Close()

	msgs, err := scanMessages(rows)
	if err != nil {
		return nil, "", false, err
	}

	hasMore := len(msgs) > limit
	if hasMore {
		msgs = msgs[:limit]
	}

	nextCursor := ""
	if len(msgs) > 0 {
		nextCursor = msgs[len(msgs)-1].ID.String()
	}

	return msgs, nextCursor, hasMore, nil
}

func (r *MessageRepository) GetThread(ctx context.Context, parentID uuid.UUID, cursor *uuid.UUID, limit int) ([]*Message, string, bool, error) {
	var rows pgx.Rows
	var err error

	if cursor == nil {
		rows, err = r.db.Query(ctx, `
			SELECT id, conversation_id, sender_id, content, type, status,
			       media_url, media_mime, media_size,
			       parent_id, thread_count, send_at, expires_at,
			       is_pinned, is_edited, is_deleted,
			       forwarded_from_id, forwarded_from_conv, forwarded_from_sender,
			       created_at, updated_at
			FROM messages
			WHERE parent_id = $1 AND is_deleted = FALSE
			ORDER BY created_at ASC
			LIMIT $2
		`, parentID, limit+1)
	} else {
		rows, err = r.db.Query(ctx, `
			SELECT id, conversation_id, sender_id, content, type, status,
			       media_url, media_mime, media_size,
			       parent_id, thread_count, send_at, expires_at,
			       is_pinned, is_edited, is_deleted,
			       forwarded_from_id, forwarded_from_conv, forwarded_from_sender,
			       created_at, updated_at
			FROM messages
			WHERE parent_id = $1 AND is_deleted = FALSE
			  AND created_at > (SELECT created_at FROM messages WHERE id = $2)
			ORDER BY created_at ASC
			LIMIT $3
		`, parentID, *cursor, limit+1)
	}
	if err != nil {
		return nil, "", false, err
	}
	defer rows.Close()

	msgs, err := scanMessages(rows)
	if err != nil {
		return nil, "", false, err
	}

	hasMore := len(msgs) > limit
	if hasMore {
		msgs = msgs[:limit]
	}

	nextCursor := ""
	if len(msgs) > 0 {
		nextCursor = msgs[len(msgs)-1].ID.String()
	}

	return msgs, nextCursor, hasMore, nil
}

func (r *MessageRepository) Edit(ctx context.Context, msgID, editorID uuid.UUID, newContent string) (*Message, []EditHistoryEntry, error) {
	tx, err := r.db.Begin(ctx)
	if err != nil {
		return nil, nil, err
	}
	defer tx.Rollback(ctx)

	// Store the current content in edit history
	if _, err := tx.Exec(ctx, `
		INSERT INTO message_edits (message_id, content)
		SELECT id, content FROM messages WHERE id = $1
	`, msgID); err != nil {
		return nil, nil, fmt.Errorf("storing edit history: %w", err)
	}

	// Update the message
	msg := &Message{}
	if err := tx.QueryRow(ctx, `
		UPDATE messages SET content = $1, is_edited = TRUE
		WHERE id = $2 AND sender_id = $3 AND is_deleted = FALSE
		RETURNING id, conversation_id, sender_id, content, type, status,
		          media_url, media_mime, media_size,
		          parent_id, thread_count, send_at, expires_at,
		          is_pinned, is_edited, is_deleted,
		          forwarded_from_id, forwarded_from_conv, forwarded_from_sender,
		          created_at, updated_at
	`, newContent, msgID, editorID).Scan(
		&msg.ID, &msg.ConversationID, &msg.SenderID, &msg.Content, &msg.Type, &msg.Status,
		&msg.MediaURL, &msg.MediaMime, &msg.MediaSize,
		&msg.ParentID, &msg.ThreadCount, &msg.SendAt, &msg.ExpiresAt,
		&msg.IsPinned, &msg.IsEdited, &msg.IsDeleted,
		&msg.ForwardedFromID, &msg.ForwardedFromConv, &msg.ForwardedFromSender,
		&msg.CreatedAt, &msg.UpdatedAt,
	); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, nil, ErrNotAuthor
		}
		return nil, nil, err
	}

	if err := tx.Commit(ctx); err != nil {
		return nil, nil, err
	}

	history, _ := r.GetEditHistory(context.Background(), msgID)
	return msg, history, nil
}

func (r *MessageRepository) GetEditHistory(ctx context.Context, msgID uuid.UUID) ([]EditHistoryEntry, error) {
	rows, err := r.db.Query(ctx, `
		SELECT content, edited_at FROM message_edits
		WHERE message_id = $1 ORDER BY edited_at ASC
	`, msgID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var entries []EditHistoryEntry
	for rows.Next() {
		var e EditHistoryEntry
		if err := rows.Scan(&e.Content, &e.EditedAt); err != nil {
			return nil, err
		}
		entries = append(entries, e)
	}
	return entries, rows.Err()
}

func (r *MessageRepository) Delete(ctx context.Context, msgID, deleterID uuid.UUID) error {
	tag, err := r.db.Exec(ctx, `
		UPDATE messages SET is_deleted = TRUE
		WHERE id = $1 AND sender_id = $2
	`, msgID, deleterID)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrNotAuthor
	}
	return nil
}

// ── Reactions ─────────────────────────────────────────────────────────────────

func (r *MessageRepository) AddReaction(ctx context.Context, msgID, userID uuid.UUID, emoji string) (*Reaction, error) {
	rxn := &Reaction{}
	err := r.db.QueryRow(ctx, `
		INSERT INTO message_reactions (message_id, user_id, emoji)
		VALUES ($1, $2, $3)
		ON CONFLICT (message_id, user_id, emoji) DO NOTHING
		RETURNING message_id, user_id, emoji, created_at
	`, msgID, userID, emoji).Scan(&rxn.MessageID, &rxn.UserID, &rxn.Emoji, &rxn.CreatedAt)
	if errors.Is(err, pgx.ErrNoRows) {
		// Already exists — return existing
		return nil, nil
	}
	return rxn, err
}

func (r *MessageRepository) RemoveReaction(ctx context.Context, msgID, userID uuid.UUID, emoji string) error {
	_, err := r.db.Exec(ctx, `
		DELETE FROM message_reactions
		WHERE message_id = $1 AND user_id = $2 AND emoji = $3
	`, msgID, userID, emoji)
	return err
}

func (r *MessageRepository) GetReactions(ctx context.Context, msgID uuid.UUID) ([]Reaction, error) {
	rows, err := r.db.Query(ctx, `
		SELECT message_id, user_id, emoji, created_at
		FROM message_reactions WHERE message_id = $1
	`, msgID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var reactions []Reaction
	for rows.Next() {
		var rx Reaction
		if err := rows.Scan(&rx.MessageID, &rx.UserID, &rx.Emoji, &rx.CreatedAt); err != nil {
			return nil, err
		}
		reactions = append(reactions, rx)
	}
	return reactions, rows.Err()
}

// ── Read Receipts ─────────────────────────────────────────────────────────────

func (r *MessageRepository) MarkRead(ctx context.Context, convID, userID uuid.UUID, msgIDs []uuid.UUID) error {
	tx, err := r.db.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)

	for _, msgID := range msgIDs {
		if _, err := tx.Exec(ctx, `
			INSERT INTO message_reads (message_id, user_id)
			VALUES ($1, $2)
			ON CONFLICT DO NOTHING
		`, msgID, userID); err != nil {
			return err
		}
	}

	// Update last_read_at in conversation_members
	if _, err := tx.Exec(ctx, `
		UPDATE conversation_members SET last_read_at = NOW()
		WHERE conversation_id = $1 AND user_id = $2
	`, convID, userID); err != nil {
		return err
	}

	return tx.Commit(ctx)
}

func (r *MessageRepository) GetUnreadCount(ctx context.Context, convID, userID uuid.UUID) (int, error) {
	var count int
	err := r.db.QueryRow(ctx, `
		SELECT COUNT(*) FROM messages m
		WHERE m.conversation_id = $1
		  AND m.sender_id <> $2
		  AND m.is_deleted = FALSE
		  AND m.status <> 'scheduled'
		  AND NOT EXISTS (
			  SELECT 1 FROM message_reads mr
			  WHERE mr.message_id = m.id AND mr.user_id = $2
		  )
	`, convID, userID).Scan(&count)
	return count, err
}

// ── Pin ───────────────────────────────────────────────────────────────────────

func (r *MessageRepository) Pin(ctx context.Context, msgID, convID, userID uuid.UUID) error {
	tx, err := r.db.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)

	if _, err := tx.Exec(ctx, `
		UPDATE messages SET is_pinned = TRUE WHERE id = $1
	`, msgID); err != nil {
		return err
	}

	if _, err := tx.Exec(ctx, `
		INSERT INTO pinned_messages (conversation_id, message_id, pinned_by)
		VALUES ($1, $2, $3) ON CONFLICT DO NOTHING
	`, convID, msgID, userID); err != nil {
		return err
	}

	return tx.Commit(ctx)
}

func (r *MessageRepository) Unpin(ctx context.Context, msgID, convID uuid.UUID) error {
	tx, err := r.db.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)

	if _, err := tx.Exec(ctx, `UPDATE messages SET is_pinned = FALSE WHERE id = $1`, msgID); err != nil {
		return err
	}
	if _, err := tx.Exec(ctx, `DELETE FROM pinned_messages WHERE conversation_id = $1 AND message_id = $2`, convID, msgID); err != nil {
		return err
	}

	return tx.Commit(ctx)
}

// ── Search ────────────────────────────────────────────────────────────────────

// SearchFilter defines multi-criteria message search options.
type SearchFilter struct {
	UserID         uuid.UUID
	Query          string
	ConversationID string
	SenderName     string
	MediaType      string
	FromDate       *time.Time
	ToDate         *time.Time
	Fuzzy          bool
	Limit          int
	Offset         int
}

func (r *MessageRepository) Search(ctx context.Context, userID uuid.UUID, query, convID string, limit, offset int) ([]*Message, int, error) {
	return r.SearchAdvanced(ctx, SearchFilter{
		UserID:         userID,
		Query:          query,
		ConversationID: convID,
		Fuzzy:          true,
		Limit:          limit,
		Offset:         offset,
	})
}

func (r *MessageRepository) SearchAdvanced(ctx context.Context, filter SearchFilter) ([]*Message, int, error) {
	if filter.Limit <= 0 || filter.Limit > 100 {
		filter.Limit = 20
	}

	baseQuery := `
		FROM messages m
		INNER JOIN conversation_members cm ON cm.conversation_id = m.conversation_id AND cm.user_id = $1
		LEFT JOIN core.users u ON u.id = m.sender_id
		WHERE m.is_deleted = FALSE
	`
	args := []interface{}{filter.UserID}
	argIdx := 2

	// Scope to single conversation
	if filter.ConversationID != "" {
		baseQuery += fmt.Sprintf(" AND m.conversation_id = $%d", argIdx)
		args = append(args, filter.ConversationID)
		argIdx++
	}

	// Filter by sender display name / phone / email
	if filter.SenderName != "" {
		baseQuery += fmt.Sprintf(" AND (u.display_name ILIKE $%d OR u.phone ILIKE $%d OR u.email ILIKE $%d)", argIdx, argIdx, argIdx)
		args = append(args, "%"+filter.SenderName+"%")
		argIdx++
	}

	// Filter by media type
	if filter.MediaType != "" {
		switch strings.ToLower(filter.MediaType) {
		case "image", "photo", "images":
			baseQuery += " AND (m.type = 'image' OR m.media_mime ILIKE 'image/%')"
		case "video", "videos":
			baseQuery += " AND (m.type = 'video' OR m.media_mime ILIKE 'video/%')"
		case "audio", "voice", "voice_note":
			baseQuery += " AND (m.type = 'audio' OR m.type = 'voice_note' OR m.media_mime ILIKE 'audio/%')"
		case "document", "file", "documents", "files":
			baseQuery += " AND (m.type = 'file' OR (m.media_url IS NOT NULL AND m.media_url != '' AND m.media_mime NOT ILIKE 'image/%' AND m.media_mime NOT ILIKE 'video/%' AND m.media_mime NOT ILIKE 'audio/%'))"
		case "media":
			baseQuery += " AND (m.media_url IS NOT NULL AND m.media_url != '')"
		default:
			baseQuery += fmt.Sprintf(" AND m.type = $%d", argIdx)
			args = append(args, filter.MediaType)
			argIdx++
		}
	}

	// Date range filters
	if filter.FromDate != nil && !filter.FromDate.IsZero() {
		baseQuery += fmt.Sprintf(" AND m.created_at >= $%d", argIdx)
		args = append(args, *filter.FromDate)
		argIdx++
	}
	if filter.ToDate != nil && !filter.ToDate.IsZero() {
		baseQuery += fmt.Sprintf(" AND m.created_at <= $%d", argIdx)
		args = append(args, *filter.ToDate)
		argIdx++
	}

	// Text search query (Hybrid Full-Text + Fuzzy Substring + Sender Name Match)
	if filter.Query != "" {
		baseQuery += fmt.Sprintf(` AND (
			m.content ILIKE $%d
			OR (m.search_vector IS NOT NULL AND m.search_vector @@ plainto_tsquery('english', $%d))
			OR u.display_name ILIKE $%d
		)`, argIdx, argIdx+1, argIdx)
		args = append(args, "%"+filter.Query+"%", filter.Query)
		argIdx += 2
	}

	var total int
	if err := r.db.QueryRow(ctx, "SELECT COUNT(*) "+baseQuery, args...).Scan(&total); err != nil {
		return nil, 0, err
	}

	args = append(args, filter.Limit, filter.Offset)
	rows, err := r.db.Query(ctx, `
		SELECT m.id, m.conversation_id, m.sender_id, m.content, m.type, m.status,
		       m.media_url, m.media_mime, m.media_size,
		       m.parent_id, m.thread_count, m.send_at, m.expires_at,
		       m.is_pinned, m.is_edited, m.is_deleted,
		       m.forwarded_from_id, m.forwarded_from_conv, m.forwarded_from_sender,
		       m.created_at, m.updated_at
		`+baseQuery+fmt.Sprintf(" ORDER BY m.created_at DESC LIMIT $%d OFFSET $%d", argIdx, argIdx+1),
		args...,
	)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()

	msgs, err := scanMessages(rows)
	return msgs, total, err
}

// ── Scheduled Messages ────────────────────────────────────────────────────────

// GetDueScheduledMessages returns all scheduled messages whose send_at <= now.
func (r *MessageRepository) GetDueScheduledMessages(ctx context.Context) ([]*Message, error) {
	rows, err := r.db.Query(ctx, `
		SELECT id, conversation_id, sender_id, content, type, status,
		       media_url, media_mime, media_size,
		       parent_id, thread_count, send_at, expires_at,
		       is_pinned, is_edited, is_deleted,
		       forwarded_from_id, forwarded_from_conv, forwarded_from_sender,
		       created_at, updated_at
		FROM messages
		WHERE status = 'scheduled' AND send_at <= NOW() AND is_deleted = FALSE
		FOR UPDATE SKIP LOCKED
	`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	return scanMessages(rows)
}

// MarkAsDelivered transitions a scheduled message to sent.
func (r *MessageRepository) MarkAsDelivered(ctx context.Context, msgID uuid.UUID) error {
	_, err := r.db.Exec(ctx, `
		UPDATE messages SET status = 'sent', send_at = NOW()
		WHERE id = $1
	`, msgID)
	return err
}

// ── Self-destruct ─────────────────────────────────────────────────────────────

// GetExpiredMessages returns messages whose expires_at has passed.
func (r *MessageRepository) GetExpiredMessages(ctx context.Context) ([]*Message, error) {
	rows, err := r.db.Query(ctx, `
		SELECT id, conversation_id, sender_id, content, type, status,
		       media_url, media_mime, media_size,
		       parent_id, thread_count, send_at, expires_at,
		       is_pinned, is_edited, is_deleted,
		       forwarded_from_id, forwarded_from_conv, forwarded_from_sender,
		       created_at, updated_at
		FROM messages
		WHERE expires_at IS NOT NULL AND expires_at <= NOW() AND is_deleted = FALSE
		FOR UPDATE SKIP LOCKED
	`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	return scanMessages(rows)
}

// ── helpers ───────────────────────────────────────────────────────────────────

func scanMessages(rows pgx.Rows) ([]*Message, error) {
	var msgs []*Message
	for rows.Next() {
		m := &Message{}
		if err := rows.Scan(
			&m.ID, &m.ConversationID, &m.SenderID, &m.Content, &m.Type, &m.Status,
			&m.MediaURL, &m.MediaMime, &m.MediaSize,
			&m.ParentID, &m.ThreadCount, &m.SendAt, &m.ExpiresAt,
			&m.IsPinned, &m.IsEdited, &m.IsDeleted,
			&m.ForwardedFromID, &m.ForwardedFromConv, &m.ForwardedFromSender,
			&m.CreatedAt, &m.UpdatedAt,
		); err != nil {
			return nil, err
		}
		msgs = append(msgs, m)
	}
	return msgs, rows.Err()
}

func uniqueIDs(ids []uuid.UUID) []uuid.UUID {
	seen := make(map[uuid.UUID]struct{}, len(ids))
	result := make([]uuid.UUID, 0, len(ids))
	for _, id := range ids {
		if _, ok := seen[id]; !ok {
			seen[id] = struct{}{}
			result = append(result, id)
		}
	}
	return result
}

// ── Forwarding ────────────────────────────────────────────────────────────────

func (r *MessageRepository) Forward(ctx context.Context, originalMsg *Message, targetConvID, senderID uuid.UUID) (*Message, error) {
	msg := &Message{
		ConversationID:      targetConvID,
		SenderID:            senderID,
		Content:             originalMsg.Content,
		Type:                originalMsg.Type,
		Status:              MessageStatusSent,
		MediaURL:            originalMsg.MediaURL,
		MediaMime:           originalMsg.MediaMime,
		MediaSize:           originalMsg.MediaSize,
		SendAt:              time.Now(),
		ForwardedFromID:     &originalMsg.ID,
		ForwardedFromConv:   &originalMsg.ConversationID,
		ForwardedFromSender: &originalMsg.SenderID,
	}

	return r.Create(ctx, msg)
}

// ── Mentions ──────────────────────────────────────────────────────────────────

func (r *MessageRepository) SaveMentions(ctx context.Context, msgID uuid.UUID, userIDs []uuid.UUID) error {
	if len(userIDs) == 0 {
		return nil
	}
	tx, err := r.db.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)

	for _, uid := range userIDs {
		if _, err := tx.Exec(ctx, `
			INSERT INTO message_mentions (message_id, user_id)
			VALUES ($1, $2)
			ON CONFLICT DO NOTHING
		`, msgID, uid); err != nil {
			return err
		}
	}
	return tx.Commit(ctx)
}

func (r *MessageRepository) GetMentions(ctx context.Context, msgID uuid.UUID) ([]uuid.UUID, error) {
	rows, err := r.db.Query(ctx, `
		SELECT user_id FROM message_mentions WHERE message_id = $1
	`, msgID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var ids []uuid.UUID
	for rows.Next() {
		var id uuid.UUID
		if err := rows.Scan(&id); err != nil {
			return nil, err
		}
		ids = append(ids, id)
	}
	return ids, rows.Err()
}
