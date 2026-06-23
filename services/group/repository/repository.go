package repository

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

type GroupMetadata struct {
	ConversationID       uuid.UUID
	Description          string
	AnnouncementsOnly    bool
	AdminsOnlyEditInfo   bool
	InviteCode           sql.NullString
	JoinApprovalRequired bool
}

type GroupJoinApproval struct {
	ID               uuid.UUID
	ConversationID   uuid.UUID
	UserID           uuid.UUID
	Status           string
	RequestedAt      time.Time
	ResolvedBy       uuid.NullUUID
	ResolvedAt       *time.Time
	UserDisplayName  string
	UserAvatarURL    string
}

type Community struct {
	ID                   uuid.UUID
	Name                 string
	Description          string
	CreatedBy            uuid.UUID
	CreatedAt            time.Time
	GroupConversationIDs []uuid.UUID
}

type BroadcastList struct {
	ID           uuid.UUID
	OwnerID      uuid.UUID
	Name         string
	CreatedAt    time.Time
	RecipientIDs []uuid.UUID
}

type GroupRepository struct {
	db *pgxpool.Pool
}

func New(db *pgxpool.Pool) *GroupRepository {
	return &GroupRepository{db: db}
}

func (r *GroupRepository) GetMetadata(ctx context.Context, convID uuid.UUID) (*GroupMetadata, error) {
	m := &GroupMetadata{}
	err := r.db.QueryRow(ctx, `
		SELECT conversation_id, description, announcements_only, admins_only_edit_info, invite_code, join_approval_required
		FROM group_metadata WHERE conversation_id = $1
	`, convID).Scan(&m.ConversationID, &m.Description, &m.AnnouncementsOnly, &m.AdminsOnlyEditInfo, &m.InviteCode, &m.JoinApprovalRequired)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			// Auto-create default metadata record
			_, err = r.db.Exec(ctx, `
				INSERT INTO group_metadata (conversation_id) VALUES ($1)
				ON CONFLICT (conversation_id) DO NOTHING
			`, convID)
			if err != nil {
				return nil, fmt.Errorf("create default metadata: %w", err)
			}
			return r.GetMetadata(ctx, convID)
		}
		return nil, err
	}
	return m, nil
}

func (r *GroupRepository) UpdateMetadata(ctx context.Context, m *GroupMetadata) error {
	_, err := r.db.Exec(ctx, `
		INSERT INTO group_metadata (conversation_id, description, announcements_only, admins_only_edit_info, invite_code, join_approval_required)
		VALUES ($1, $2, $3, $4, $5, $6)
		ON CONFLICT (conversation_id) DO UPDATE SET
			description = EXCLUDED.description,
			announcements_only = EXCLUDED.announcements_only,
			admins_only_edit_info = EXCLUDED.admins_only_edit_info,
			invite_code = EXCLUDED.invite_code,
			join_approval_required = EXCLUDED.join_approval_required
	`, m.ConversationID, m.Description, m.AnnouncementsOnly, m.AdminsOnlyEditInfo, m.InviteCode, m.JoinApprovalRequired)
	return err
}

func (r *GroupRepository) GetUserRole(ctx context.Context, convID, userID uuid.UUID) (string, error) {
	var role string
	err := r.db.QueryRow(ctx, `
		SELECT role FROM conversation_members WHERE conversation_id = $1 AND user_id = $2
	`, convID, userID).Scan(&role)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return "", nil // Not a member
		}
		return "", err
	}
	return role, nil
}

func (r *GroupRepository) SetUserRole(ctx context.Context, convID, userID uuid.UUID, role string) error {
	res, err := r.db.Exec(ctx, `
		UPDATE conversation_members SET role = $1 WHERE conversation_id = $2 AND user_id = $3
	`, role, convID, userID)
	if err != nil {
		return err
	}
	if res.RowsAffected() == 0 {
		return fmt.Errorf("member not found in group")
	}
	return nil
}

func (r *GroupRepository) GenerateInviteCode(ctx context.Context, convID uuid.UUID, code string) error {
	_, err := r.GetMetadata(ctx, convID)
	if err != nil {
		return err
	}
	_, err = r.db.Exec(ctx, `
		UPDATE group_metadata SET invite_code = $1 WHERE conversation_id = $2
	`, code, convID)
	return err
}

func (r *GroupRepository) GetGroupIDByInviteCode(ctx context.Context, code string) (uuid.UUID, bool, error) {
	var convID uuid.UUID
	var approvalRequired bool
	err := r.db.QueryRow(ctx, `
		SELECT conversation_id, join_approval_required FROM group_metadata WHERE invite_code = $1
	`, code).Scan(&convID, &approvalRequired)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return uuid.Nil, false, fmt.Errorf("invalid invite code")
		}
		return uuid.Nil, false, err
	}
	return convID, approvalRequired, nil
}

func (r *GroupRepository) JoinGroupDirect(ctx context.Context, convID, userID uuid.UUID) error {
	_, err := r.db.Exec(ctx, `
		INSERT INTO conversation_members (conversation_id, user_id, role)
		VALUES ($1, $2, 'member')
		ON CONFLICT (conversation_id, user_id) DO NOTHING
	`, convID, userID)
	return err
}

func (r *GroupRepository) CreateJoinRequest(ctx context.Context, convID, userID uuid.UUID) error {
	_, err := r.db.Exec(ctx, `
		INSERT INTO group_join_approvals (conversation_id, user_id, status)
		VALUES ($1, $2, 'pending')
		ON CONFLICT (conversation_id, user_id, status) DO NOTHING
	`, convID, userID)
	return err
}

func (r *GroupRepository) GetPendingApprovals(ctx context.Context, convID uuid.UUID) ([]*GroupJoinApproval, error) {
	rows, err := r.db.Query(ctx, `
		SELECT a.id, a.conversation_id, a.user_id, a.status, a.requested_at, u.display_name, u.avatar_url
		FROM group_join_approvals a
		JOIN users u ON a.user_id = u.id
		WHERE a.conversation_id = $1 AND a.status = 'pending'
	`, convID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var approvals []*GroupJoinApproval
	for rows.Next() {
		a := &GroupJoinApproval{}
		err := rows.Scan(&a.ID, &a.ConversationID, &a.UserID, &a.Status, &a.RequestedAt, &a.UserDisplayName, &a.UserAvatarURL)
		if err != nil {
			return nil, err
		}
		approvals = append(approvals, a)
	}
	return approvals, nil
}

func (r *GroupRepository) ResolveApproval(ctx context.Context, approvalID, resolverID uuid.UUID, approve bool) (uuid.UUID, uuid.UUID, error) {
	var convID, userID uuid.UUID
	err := r.db.QueryRow(ctx, `
		SELECT conversation_id, user_id FROM group_join_approvals WHERE id = $1 AND status = 'pending'
	`, approvalID).Scan(&convID, &userID)
	if err != nil {
		return uuid.Nil, uuid.Nil, err
	}

	tx, err := r.db.Begin(ctx)
	if err != nil {
		return uuid.Nil, uuid.Nil, err
	}
	defer tx.Rollback(ctx)

	status := "rejected"
	if approve {
		status = "approved"
	}

	_, err = tx.Exec(ctx, `
		UPDATE group_join_approvals
		SET status = $1, resolved_by = $2, resolved_at = NOW()
		WHERE id = $3
	`, status, resolverID, approvalID)
	if err != nil {
		return uuid.Nil, uuid.Nil, err
	}

	if approve {
		_, err = tx.Exec(ctx, `
			INSERT INTO conversation_members (conversation_id, user_id, role)
			VALUES ($1, $2, 'member')
			ON CONFLICT (conversation_id, user_id) DO NOTHING
		`, convID, userID)
		if err != nil {
			return uuid.Nil, uuid.Nil, err
		}
	}

	if err := tx.Commit(ctx); err != nil {
		return uuid.Nil, uuid.Nil, err
	}

	return convID, userID, nil
}

func (r *GroupRepository) CreateCommunity(ctx context.Context, name, description string, creatorID uuid.UUID) (*Community, error) {
	c := &Community{
		Name:                 name,
		Description:          description,
		CreatedBy:            creatorID,
		GroupConversationIDs: []uuid.UUID{},
	}
	err := r.db.QueryRow(ctx, `
		INSERT INTO communities (name, description, created_by)
		VALUES ($1, $2, $3)
		RETURNING id, created_at
	`, name, description, creatorID).Scan(&c.ID, &c.CreatedAt)
	if err != nil {
		return nil, err
	}
	return c, nil
}

func (r *GroupRepository) AddGroupToCommunity(ctx context.Context, communityID, conversationID, requesterID uuid.UUID) error {
	// Verify community creator is the requester
	var createdBy uuid.UUID
	err := r.db.QueryRow(ctx, `SELECT created_by FROM communities WHERE id = $1`, communityID).Scan(&createdBy)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return fmt.Errorf("community not found")
		}
		return err
	}
	if createdBy != requesterID {
		return fmt.Errorf("only the community creator can add groups")
	}

	_, err = r.db.Exec(ctx, `
		INSERT INTO community_groups (community_id, conversation_id)
		VALUES ($1, $2)
		ON CONFLICT (community_id, conversation_id) DO NOTHING
	`, communityID, conversationID)
	return err
}

func (r *GroupRepository) RemoveGroupFromCommunity(ctx context.Context, communityID, conversationID, requesterID uuid.UUID) error {
	// Verify community creator is the requester
	var createdBy uuid.UUID
	err := r.db.QueryRow(ctx, `SELECT created_by FROM communities WHERE id = $1`, communityID).Scan(&createdBy)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return fmt.Errorf("community not found")
		}
		return err
	}
	if createdBy != requesterID {
		return fmt.Errorf("only the community creator can remove groups")
	}

	res, err := r.db.Exec(ctx, `
		DELETE FROM community_groups
		WHERE community_id = $1 AND conversation_id = $2
	`, communityID, conversationID)
	if err != nil {
		return err
	}
	if res.RowsAffected() == 0 {
		return fmt.Errorf("group not found in community")
	}
	return nil
}

func (r *GroupRepository) GetCommunity(ctx context.Context, communityID uuid.UUID) (*Community, error) {
	c := &Community{
		GroupConversationIDs: []uuid.UUID{},
	}
	err := r.db.QueryRow(ctx, `
		SELECT id, name, description, created_by, created_at
		FROM communities WHERE id = $1
	`, communityID).Scan(&c.ID, &c.Name, &c.Description, &c.CreatedBy, &c.CreatedAt)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, fmt.Errorf("community not found")
		}
		return nil, err
	}

	// Fetch group conversation IDs
	rows, err := r.db.Query(ctx, `
		SELECT conversation_id FROM community_groups WHERE community_id = $1
	`, communityID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	for rows.Next() {
		var gID uuid.UUID
		if err := rows.Scan(&gID); err != nil {
			return nil, err
		}
		c.GroupConversationIDs = append(c.GroupConversationIDs, gID)
	}

	return c, nil
}

func (r *GroupRepository) ListCommunities(ctx context.Context, userID uuid.UUID) ([]*Community, error) {
	var rows pgx.Rows
	var err error
	if userID == uuid.Nil {
		rows, err = r.db.Query(ctx, `
			SELECT id, name, description, created_by, created_at FROM communities
		`)
	} else {
		rows, err = r.db.Query(ctx, `
			SELECT DISTINCT c.id, c.name, c.description, c.created_by, c.created_at
			FROM communities c
			LEFT JOIN community_groups cg ON c.id = cg.community_id
			LEFT JOIN conversation_members cm ON cg.conversation_id = cm.conversation_id
			WHERE c.created_by = $1 OR cm.user_id = $1
		`, userID)
	}
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var communities []*Community
	for rows.Next() {
		c := &Community{
			GroupConversationIDs: []uuid.UUID{},
		}
		err := rows.Scan(&c.ID, &c.Name, &c.Description, &c.CreatedBy, &c.CreatedAt)
		if err != nil {
			return nil, err
		}
		communities = append(communities, c)
	}

	// For each community, fetch its groups
	for _, c := range communities {
		gRows, err := r.db.Query(ctx, `
			SELECT conversation_id FROM community_groups WHERE community_id = $1
		`, c.ID)
		if err != nil {
			return nil, err
		}
		for gRows.Next() {
			var gID uuid.UUID
			if err := gRows.Scan(&gID); err != nil {
				gRows.Close()
				return nil, err
			}
			c.GroupConversationIDs = append(c.GroupConversationIDs, gID)
		}
		gRows.Close()
	}

	return communities, nil
}

func (r *GroupRepository) CreateBroadcastList(ctx context.Context, name string, ownerID uuid.UUID, recipientIDs []uuid.UUID) (*BroadcastList, error) {
	tx, err := r.db.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback(ctx)

	b := &BroadcastList{
		OwnerID:      ownerID,
		Name:         name,
		RecipientIDs: recipientIDs,
	}

	err = tx.QueryRow(ctx, `
		INSERT INTO broadcast_lists (owner_id, name)
		VALUES ($1, $2)
		RETURNING id, created_at
	`, ownerID, name).Scan(&b.ID, &b.CreatedAt)
	if err != nil {
		return nil, err
	}

	for _, rID := range recipientIDs {
		_, err = tx.Exec(ctx, `
			INSERT INTO broadcast_recipients (broadcast_list_id, user_id)
			VALUES ($1, $2)
		`, b.ID, rID)
		if err != nil {
			return nil, err
		}
	}

	if err := tx.Commit(ctx); err != nil {
		return nil, err
	}

	return b, nil
}

func (r *GroupRepository) DeleteBroadcastList(ctx context.Context, listID, ownerID uuid.UUID) error {
	res, err := r.db.Exec(ctx, `
		DELETE FROM broadcast_lists WHERE id = $1 AND owner_id = $2
	`, listID, ownerID)
	if err != nil {
		return err
	}
	if res.RowsAffected() == 0 {
		return fmt.Errorf("broadcast list not found or unauthorized")
	}
	return nil
}

func (r *GroupRepository) AddBroadcastRecipient(ctx context.Context, listID, recipientID, ownerID uuid.UUID) error {
	// Verify ownership
	var exists bool
	err := r.db.QueryRow(ctx, `
		SELECT EXISTS(SELECT 1 FROM broadcast_lists WHERE id = $1 AND owner_id = $2)
	`, listID, ownerID).Scan(&exists)
	if err != nil {
		return err
	}
	if !exists {
		return fmt.Errorf("broadcast list not found or unauthorized")
	}

	_, err = r.db.Exec(ctx, `
		INSERT INTO broadcast_recipients (broadcast_list_id, user_id)
		VALUES ($1, $2)
		ON CONFLICT (broadcast_list_id, user_id) DO NOTHING
	`, listID, recipientID)
	return err
}

func (r *GroupRepository) RemoveBroadcastRecipient(ctx context.Context, listID, recipientID, ownerID uuid.UUID) error {
	// Verify ownership
	var exists bool
	err := r.db.QueryRow(ctx, `
		SELECT EXISTS(SELECT 1 FROM broadcast_lists WHERE id = $1 AND owner_id = $2)
	`, listID, ownerID).Scan(&exists)
	if err != nil {
		return err
	}
	if !exists {
		return fmt.Errorf("broadcast list not found or unauthorized")
	}

	res, err := r.db.Exec(ctx, `
		DELETE FROM broadcast_recipients
		WHERE broadcast_list_id = $1 AND user_id = $2
	`, listID, recipientID)
	if err != nil {
		return err
	}
	if res.RowsAffected() == 0 {
		return fmt.Errorf("recipient not found in broadcast list")
	}
	return nil
}

func (r *GroupRepository) GetBroadcastList(ctx context.Context, listID, ownerID uuid.UUID) (*BroadcastList, error) {
	b := &BroadcastList{
		RecipientIDs: []uuid.UUID{},
	}
	err := r.db.QueryRow(ctx, `
		SELECT id, owner_id, name, created_at
		FROM broadcast_lists
		WHERE id = $1 AND owner_id = $2
	`, listID, ownerID).Scan(&b.ID, &b.OwnerID, &b.Name, &b.CreatedAt)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, fmt.Errorf("broadcast list not found or unauthorized")
		}
		return nil, err
	}

	rows, err := r.db.Query(ctx, `
		SELECT user_id FROM broadcast_recipients WHERE broadcast_list_id = $1
	`, listID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	for rows.Next() {
		var uID uuid.UUID
		if err := rows.Scan(&uID); err != nil {
			return nil, err
		}
		b.RecipientIDs = append(b.RecipientIDs, uID)
	}

	return b, nil
}
