package repository

import (
	"context"
	"fmt"
	"math"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
)

type SocialRepository struct {
	db *pgxpool.Pool
}

func NewSocialRepository(db *pgxpool.Pool) *SocialRepository {
	return &SocialRepository{db: db}
}

// ── Follows ─────────────────────────────────────────────────────────────────

func (r *SocialRepository) Follow(ctx context.Context, followerID, followedID string) error {
	_, err := r.db.Exec(ctx,
		`INSERT INTO follows (follower_id, followed_id) VALUES ($1, $2) ON CONFLICT DO NOTHING`,
		followerID, followedID)
	return err
}

func (r *SocialRepository) Unfollow(ctx context.Context, followerID, followedID string) error {
	_, err := r.db.Exec(ctx,
		`DELETE FROM follows WHERE follower_id = $1 AND followed_id = $2`,
		followerID, followedID)
	return err
}

func (r *SocialRepository) GetFollowers(ctx context.Context, userID string, limit, offset int) ([]string, int, error) {
	var total int
	r.db.QueryRow(ctx, `SELECT COUNT(*) FROM follows WHERE followed_id = $1`, userID).Scan(&total)

	rows, err := r.db.Query(ctx,
		`SELECT follower_id FROM follows WHERE followed_id = $1 ORDER BY created_at DESC LIMIT $2 OFFSET $3`,
		userID, limit, offset)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()

	var ids []string
	for rows.Next() {
		var id string
		rows.Scan(&id)
		ids = append(ids, id)
	}
	return ids, total, nil
}

func (r *SocialRepository) GetFollowing(ctx context.Context, userID string, limit, offset int) ([]string, int, error) {
	var total int
	r.db.QueryRow(ctx, `SELECT COUNT(*) FROM follows WHERE follower_id = $1`, userID).Scan(&total)

	rows, err := r.db.Query(ctx,
		`SELECT followed_id FROM follows WHERE follower_id = $1 ORDER BY created_at DESC LIMIT $2 OFFSET $3`,
		userID, limit, offset)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()

	var ids []string
	for rows.Next() {
		var id string
		rows.Scan(&id)
		ids = append(ids, id)
	}
	return ids, total, nil
}

// ── Moments ─────────────────────────────────────────────────────────────────

type Moment struct {
	ID           string
	UserID       string
	Content      string
	MediaURL     string
	MediaType    string
	Visibility   string
	LikeCount    int
	CommentCount int
	CreatedAt    time.Time
}

func (r *SocialRepository) CreateMoment(ctx context.Context, userID, content, mediaURL, mediaType, visibility string) (*Moment, error) {
	id := uuid.New().String()
	if visibility == "" {
		visibility = "public"
	}
	_, err := r.db.Exec(ctx,
		`INSERT INTO moments (id, user_id, content, media_url, media_type, visibility) VALUES ($1, $2, $3, $4, $5, $6)`,
		id, userID, content, mediaURL, mediaType, visibility)
	if err != nil {
		return nil, fmt.Errorf("create moment: %w", err)
	}
	return &Moment{ID: id, UserID: userID, Content: content, MediaURL: mediaURL,
		MediaType: mediaType, Visibility: visibility, CreatedAt: time.Now()}, nil
}

func (r *SocialRepository) LikeMoment(ctx context.Context, userID, momentID string) error {
	id := uuid.New().String()
	_, err := r.db.Exec(ctx,
		`INSERT INTO moment_likes (id, moment_id, user_id) VALUES ($1, $2, $3) ON CONFLICT DO NOTHING`,
		id, momentID, userID)
	if err == nil {
		r.db.Exec(ctx, `UPDATE moments SET like_count = like_count + 1 WHERE id = $1`, momentID)
	}
	return err
}

func (r *SocialRepository) GetMomentsFeed(ctx context.Context, userID string, limit, offset int) ([]*Moment, int, error) {
	var total int
	r.db.QueryRow(ctx,
		`SELECT COUNT(*) FROM moments m
		 WHERE m.user_id IN (SELECT followed_id FROM follows WHERE follower_id = $1)
		 OR m.user_id = $1`, userID).Scan(&total)

	rows, err := r.db.Query(ctx,
		`SELECT id, user_id, COALESCE(content,''), COALESCE(media_url,''), COALESCE(media_type,''),
		 visibility, like_count, comment_count, created_at
		 FROM moments
		 WHERE user_id IN (SELECT followed_id FROM follows WHERE follower_id = $1) OR user_id = $1
		 ORDER BY created_at DESC LIMIT $2 OFFSET $3`, userID, limit, offset)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()

	var moments []*Moment
	for rows.Next() {
		m := &Moment{}
		rows.Scan(&m.ID, &m.UserID, &m.Content, &m.MediaURL, &m.MediaType,
			&m.Visibility, &m.LikeCount, &m.CommentCount, &m.CreatedAt)
		moments = append(moments, m)
	}
	return moments, total, nil
}

// ── Nearby ──────────────────────────────────────────────────────────────────

type NearbyUser struct {
	UserID     string
	DistanceKM float64
}

func (r *SocialRepository) SetNearbyVisible(ctx context.Context, userID string, lat, lon float64, visible bool, radiusKM int) error {
	if radiusKM <= 0 {
		radiusKM = 5
	}
	_, err := r.db.Exec(ctx,
		`INSERT INTO nearby_users (user_id, latitude, longitude, is_visible, radius_km)
		 VALUES ($1, $2, $3, $4, $5)
		 ON CONFLICT (user_id) DO UPDATE SET latitude=$2, longitude=$3, is_visible=$4, radius_km=$5, updated_at=NOW()`,
		userID, lat, lon, visible, radiusKM)
	return err
}

func (r *SocialRepository) GetNearbyUsers(ctx context.Context, userID string, radiusKM, limit int) ([]*NearbyUser, error) {
	var myLat, myLon float64
	err := r.db.QueryRow(ctx,
		`SELECT latitude, longitude FROM nearby_users WHERE user_id = $1 AND is_visible = TRUE`, userID).
		Scan(&myLat, &myLon)
	if err != nil {
		return nil, fmt.Errorf("user not visible: %w", err)
	}

	rows, err := r.db.Query(ctx,
		`SELECT user_id, latitude, longitude FROM nearby_users
		 WHERE user_id != $1 AND is_visible = TRUE`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var users []*NearbyUser
	for rows.Next() {
		var uid string
		var lat, lon float64
		rows.Scan(&uid, &lat, &lon)
		dist := haversine(myLat, myLon, lat, lon)
		if dist <= float64(radiusKM) {
			users = append(users, &NearbyUser{UserID: uid, DistanceKM: math.Round(dist*100) / 100})
		}
		if len(users) >= limit {
			break
		}
	}
	return users, nil
}

func haversine(lat1, lon1, lat2, lon2 float64) float64 {
	const R = 6371
	dLat := (lat2 - lat1) * math.Pi / 180
	dLon := (lon2 - lon1) * math.Pi / 180
	a := math.Sin(dLat/2)*math.Sin(dLat/2) +
		math.Cos(lat1*math.Pi/180)*math.Cos(lat2*math.Pi/180)*
			math.Sin(dLon/2)*math.Sin(dLon/2)
	return R * 2 * math.Atan2(math.Sqrt(a), math.Sqrt(1-a))
}

// ── Audio Rooms ─────────────────────────────────────────────────────────────

type AudioRoom struct {
	ID               string
	Title            string
	CreatedBy        string
	IsActive         bool
	MaxSpeakers      int
	ParticipantCount int
	CreatedAt        time.Time
}

func (r *SocialRepository) CreateAudioRoom(ctx context.Context, userID, title string, maxSpeakers int) (*AudioRoom, error) {
	id := uuid.New().String()
	_, err := r.db.Exec(ctx,
		`INSERT INTO audio_rooms (id, title, created_by, max_speakers) VALUES ($1, $2, $3, $4)`,
		id, userID, title, maxSpeakers)
	if err != nil {
		return nil, err
	}
	return &AudioRoom{ID: id, Title: title, CreatedBy: userID, IsActive: true,
		MaxSpeakers: maxSpeakers, CreatedAt: time.Now()}, nil
}

func (r *SocialRepository) JoinAudioRoom(ctx context.Context, userID, roomID, role string) error {
	id := uuid.New().String()
	if role == "" {
		role = "listener"
	}
	_, err := r.db.Exec(ctx,
		`INSERT INTO audio_room_participants (id, room_id, user_id, role) VALUES ($1, $2, $3, $4) ON CONFLICT DO NOTHING`,
		id, roomID, userID, role)
	if err == nil {
		r.db.Exec(ctx, `UPDATE audio_rooms SET participant_count = participant_count + 1 WHERE id = $1`, roomID)
	}
	return err
}

func (r *SocialRepository) LeaveAudioRoom(ctx context.Context, userID, roomID string) error {
	tag, _ := r.db.Exec(ctx,
		`DELETE FROM audio_room_participants WHERE room_id = $1 AND user_id = $2`, roomID, userID)
	if tag.RowsAffected() > 0 {
		r.db.Exec(ctx, `UPDATE audio_rooms SET participant_count = GREATEST(participant_count - 1, 0) WHERE id = $1`, roomID)
	}
	return nil
}

func (r *SocialRepository) ListAudioRooms(ctx context.Context, limit, offset int) ([]*AudioRoom, int, error) {
	var total int
	r.db.QueryRow(ctx, `SELECT COUNT(*) FROM audio_rooms WHERE is_active = TRUE`).Scan(&total)

	rows, err := r.db.Query(ctx,
		`SELECT id, title, created_by, is_active, max_speakers, participant_count, created_at
		 FROM audio_rooms WHERE is_active = TRUE ORDER BY created_at DESC LIMIT $1 OFFSET $2`,
		limit, offset)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()

	var rooms []*AudioRoom
	for rows.Next() {
		rm := &AudioRoom{}
		rows.Scan(&rm.ID, &rm.Title, &rm.CreatedBy, &rm.IsActive, &rm.MaxSpeakers, &rm.ParticipantCount, &rm.CreatedAt)
		rooms = append(rooms, rm)
	}
	return rooms, total, nil
}
