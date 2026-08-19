package repository

import (
	"context"
	"errors"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

type Story struct {
	ID              uuid.UUID
	UserID          uuid.UUID
	UserDisplayName string
	UserAvatarURL   string
	MediaURL        string
	MediaType       string
	Content         string
	BackgroundColor string
	FontStyle       string
	ExpiresAt       time.Time
	CreatedAt       time.Time
	Viewed          bool
}

type StoryViewer struct {
	UserID      uuid.UUID
	DisplayName string
	AvatarURL   string
	ViewedAt    time.Time
}

type UserStories struct {
	UserID          uuid.UUID
	UserDisplayName string
	UserAvatarURL   string
	Stories         []*Story
}

type StoryRepository struct {
	db *pgxpool.Pool
}

func New(db *pgxpool.Pool) *StoryRepository {
	return &StoryRepository{db: db}
}

func (r *StoryRepository) Create(ctx context.Context, s *Story) error {
	if s.ID == uuid.Nil {
		s.ID = uuid.New()
	}
	if s.CreatedAt.IsZero() {
		s.CreatedAt = time.Now()
	}
	if s.ExpiresAt.IsZero() {
		s.ExpiresAt = s.CreatedAt.Add(24 * time.Hour)
	}

	_, err := r.db.Exec(ctx, `
		INSERT INTO stories (id, user_id, media_url, media_type, content, background_color, font_style, expires_at, created_at)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)
	`, s.ID, s.UserID, s.MediaURL, s.MediaType, s.Content, s.BackgroundColor, s.FontStyle, s.ExpiresAt, s.CreatedAt)
	return err
}

func (r *StoryRepository) GetByID(ctx context.Context, id uuid.UUID) (*Story, error) {
	s := &Story{}
	err := r.db.QueryRow(ctx, `
		SELECT s.id, s.user_id, u.display_name, u.avatar_url, s.media_url, s.media_type, s.content, s.background_color, s.font_style, s.expires_at, s.created_at
		FROM stories s
		JOIN users u ON s.user_id = u.id
		WHERE s.id = $1
	`, id).Scan(&s.ID, &s.UserID, &s.UserDisplayName, &s.UserAvatarURL, &s.MediaURL, &s.MediaType, &s.Content, &s.BackgroundColor, &s.FontStyle, &s.ExpiresAt, &s.CreatedAt)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, errors.New("story not found")
		}
		return nil, err
	}
	return s, nil
}

func (r *StoryRepository) Delete(ctx context.Context, storyID, userID uuid.UUID) error {
	res, err := r.db.Exec(ctx, `
		DELETE FROM stories WHERE id = $1 AND user_id = $2
	`, storyID, userID)
	if err != nil {
		return err
	}
	if res.RowsAffected() == 0 {
		return errors.New("story not found or not owned by user")
	}
	return nil
}

func (r *StoryRepository) GetStoriesFeed(ctx context.Context, requesterID uuid.UUID) ([]*UserStories, error) {
	rows, err := r.db.Query(ctx, `
		SELECT s.id, s.user_id, COALESCE(NULLIF(u.display_name, ''), u.phone, u.pin, 'Contact'), u.avatar_url, s.media_url, s.media_type, s.content, s.background_color, s.font_style, s.expires_at, s.created_at,
		       EXISTS(SELECT 1 FROM story_views WHERE story_id = s.id AND viewer_id = $1) as viewed
		FROM stories s
		JOIN core.users u ON s.user_id = u.id
		WHERE s.expires_at > NOW()
		  AND (
			    s.user_id = $1
			 OR EXISTS (
				SELECT 1
				FROM social.user_followers uf
				WHERE uf.follower_id = $1 AND uf.following_id = s.user_id
			)
			 OR EXISTS (
				SELECT 1
				FROM chat.conversation_members requester_member
				JOIN chat.conversation_members target_member
				  ON target_member.conversation_id = requester_member.conversation_id
				WHERE requester_member.user_id = $1
				  AND target_member.user_id = s.user_id
			)
		)
		ORDER BY s.created_at DESC
	`, requesterID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	// Group stories by UserID in memory
	userMap := make(map[uuid.UUID]*UserStories)
	var orderedUserIDs []uuid.UUID

	for rows.Next() {
		s := &Story{}
		err := rows.Scan(
			&s.ID, &s.UserID, &s.UserDisplayName, &s.UserAvatarURL,
			&s.MediaURL, &s.MediaType, &s.Content, &s.BackgroundColor, &s.FontStyle,
			&s.ExpiresAt, &s.CreatedAt, &s.Viewed,
		)
		if err != nil {
			return nil, err
		}

		us, exists := userMap[s.UserID]
		if !exists {
			us = &UserStories{
				UserID:          s.UserID,
				UserDisplayName: s.UserDisplayName,
				UserAvatarURL:   s.UserAvatarURL,
				Stories:         []*Story{},
			}
			userMap[s.UserID] = us
			orderedUserIDs = append(orderedUserIDs, s.UserID)
		}
		us.Stories = append(us.Stories, s)
	}

	feed := make([]*UserStories, len(orderedUserIDs))
	for i, uid := range orderedUserIDs {
		feed[i] = userMap[uid]
	}

	return feed, nil
}

func (r *StoryRepository) ViewStory(ctx context.Context, storyID, viewerID uuid.UUID) error {
	_, err := r.db.Exec(ctx, `
		INSERT INTO story_views (story_id, viewer_id, viewed_at)
		VALUES ($1, $2, NOW())
		ON CONFLICT (story_id, viewer_id) DO NOTHING
	`, storyID, viewerID)
	return err
}

func (r *StoryRepository) GetViewerList(ctx context.Context, storyID uuid.UUID) ([]*StoryViewer, error) {
	rows, err := r.db.Query(ctx, `
		SELECT v.viewer_id, u.display_name, u.avatar_url, v.viewed_at
		FROM story_views v
		JOIN users u ON v.viewer_id = u.id
		WHERE v.story_id = $1
		ORDER BY v.viewed_at DESC
	`, storyID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var viewers []*StoryViewer
	for rows.Next() {
		v := &StoryViewer{}
		err := rows.Scan(&v.UserID, &v.DisplayName, &v.AvatarURL, &v.ViewedAt)
		if err != nil {
			return nil, err
		}
		viewers = append(viewers, v)
	}
	return viewers, nil
}

func (r *StoryRepository) DeleteExpiredStories(ctx context.Context) (int64, error) {
	res, err := r.db.Exec(ctx, `
		DELETE FROM stories WHERE expires_at <= NOW()
	`)
	if err != nil {
		return 0, err
	}
	return res.RowsAffected(), nil
}
