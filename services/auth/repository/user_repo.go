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
	"golang.org/x/crypto/bcrypt"
)

// User is the domain model for a user.
type User struct {
	ID            uuid.UUID
	Email         string
	PasswordHash  string
	DisplayName   string
	AvatarURL     string
	StatusText    string
	IsOnline      bool
	LastSeen      time.Time
	CreatedAt     time.Time
	UpdatedAt     time.Time
	Phone         string
	PhoneVerified bool
}

// RefreshToken represents a stored refresh token JTI.
type RefreshToken struct {
	ID        uuid.UUID
	UserID    uuid.UUID
	JTI       string
	ExpiresAt time.Time
	Revoked   bool
	CreatedAt time.Time
}

var (
	ErrUserNotFound      = errors.New("user not found")
	ErrEmailAlreadyTaken = errors.New("email already taken")
	ErrInvalidPassword   = errors.New("invalid password")
	ErrTokenRevoked      = errors.New("refresh token has been revoked")
	ErrTokenNotFound     = errors.New("refresh token not found")
)

// UserRepository handles all user and auth-related DB operations.
type UserRepository struct {
	db *pgxpool.Pool
}

// NewUserRepository creates a new repository.
func NewUserRepository(db *pgxpool.Pool) *UserRepository {
	return &UserRepository{db: db}
}

// CreateUser inserts a new user, hashing the password with bcrypt.
func (r *UserRepository) CreateUser(ctx context.Context, email, password, displayName string) (*User, error) {
	hash, err := bcrypt.GenerateFromPassword([]byte(password), bcrypt.DefaultCost)
	if err != nil {
		return nil, fmt.Errorf("hashing password: %w", err)
	}

	user := &User{}
	err = r.db.QueryRow(ctx, `
		INSERT INTO users (email, password_hash, display_name)
		VALUES ($1, $2, $3)
		RETURNING id, email, password_hash, display_name, avatar_url, status_text,
		          is_online, last_seen, created_at, updated_at, COALESCE(phone, ''), phone_verified
	`, email, string(hash), displayName).Scan(
		&user.ID, &user.Email, &user.PasswordHash, &user.DisplayName,
		&user.AvatarURL, &user.StatusText, &user.IsOnline,
		&user.LastSeen, &user.CreatedAt, &user.UpdatedAt, &user.Phone, &user.PhoneVerified,
	)
	if err != nil {
		if isUniqueViolation(err) {
			return nil, ErrEmailAlreadyTaken
		}
		return nil, fmt.Errorf("inserting user: %w", err)
	}

	return user, nil
}

// GetUserByEmail fetches a user by email, returning ErrUserNotFound if missing.
func (r *UserRepository) GetUserByEmail(ctx context.Context, email string) (*User, error) {
	user := &User{}
	err := r.db.QueryRow(ctx, `
		SELECT id, email, password_hash, display_name, avatar_url, status_text,
		       is_online, last_seen, created_at, updated_at, COALESCE(phone, ''), phone_verified
		FROM users WHERE email = $1
	`, email).Scan(
		&user.ID, &user.Email, &user.PasswordHash, &user.DisplayName,
		&user.AvatarURL, &user.StatusText, &user.IsOnline,
		&user.LastSeen, &user.CreatedAt, &user.UpdatedAt, &user.Phone, &user.PhoneVerified,
	)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, ErrUserNotFound
	}
	if err != nil {
		return nil, fmt.Errorf("fetching user by email: %w", err)
	}
	return user, nil
}

// GetUserByID fetches a user by primary key.
func (r *UserRepository) GetUserByID(ctx context.Context, id uuid.UUID) (*User, error) {
	user := &User{}
	err := r.db.QueryRow(ctx, `
		SELECT id, email, password_hash, display_name, avatar_url, status_text,
		       is_online, last_seen, created_at, updated_at, COALESCE(phone, ''), phone_verified
		FROM users WHERE id = $1
	`, id).Scan(
		&user.ID, &user.Email, &user.PasswordHash, &user.DisplayName,
		&user.AvatarURL, &user.StatusText, &user.IsOnline,
		&user.LastSeen, &user.CreatedAt, &user.UpdatedAt, &user.Phone, &user.PhoneVerified,
	)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, ErrUserNotFound
	}
	if err != nil {
		return nil, fmt.Errorf("fetching user by ID: %w", err)
	}
	return user, nil
}

// GetUsersByIDs batch-fetches multiple users.
func (r *UserRepository) GetUsersByIDs(ctx context.Context, ids []uuid.UUID) ([]*User, error) {
	rows, err := r.db.Query(ctx, `
		SELECT id, email, password_hash, display_name, avatar_url, status_text,
		       is_online, last_seen, created_at, updated_at, COALESCE(phone, ''), phone_verified
		FROM users WHERE id = ANY($1)
	`, ids)
	if err != nil {
		return nil, fmt.Errorf("batch fetching users: %w", err)
	}
	defer rows.Close()

	var users []*User
	for rows.Next() {
		u := &User{}
		if err := rows.Scan(
			&u.ID, &u.Email, &u.PasswordHash, &u.DisplayName,
			&u.AvatarURL, &u.StatusText, &u.IsOnline,
			&u.LastSeen, &u.CreatedAt, &u.UpdatedAt, &u.Phone, &u.PhoneVerified,
		); err != nil {
			return nil, err
		}
		users = append(users, u)
	}
	return users, rows.Err()
}

// UpdateUser updates display_name, avatar_url, and status_text.
// Empty strings are ignored (no change).
func (r *UserRepository) UpdateUser(ctx context.Context, id uuid.UUID, displayName, avatarURL, statusText string) (*User, error) {
	user := &User{}
	err := r.db.QueryRow(ctx, `
		UPDATE users SET
			display_name = CASE WHEN $2 <> '' THEN $2 ELSE display_name END,
			avatar_url   = CASE WHEN $3 <> '' THEN $3 ELSE avatar_url END,
			status_text  = CASE WHEN $4 <> '' THEN $4 ELSE status_text END
		WHERE id = $1
		RETURNING id, email, password_hash, display_name, avatar_url, status_text,
		          is_online, last_seen, created_at, updated_at, COALESCE(phone, ''), phone_verified
	`, id, displayName, avatarURL, statusText).Scan(
		&user.ID, &user.Email, &user.PasswordHash, &user.DisplayName,
		&user.AvatarURL, &user.StatusText, &user.IsOnline,
		&user.LastSeen, &user.CreatedAt, &user.UpdatedAt, &user.Phone, &user.PhoneVerified,
	)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, ErrUserNotFound
	}
	if err != nil {
		return nil, fmt.Errorf("updating user: %w", err)
	}
	return user, nil
}

// SetPresence updates a user's online status and last_seen.
func (r *UserRepository) SetPresence(ctx context.Context, id uuid.UUID, isOnline bool) error {
	_, err := r.db.Exec(ctx, `
		UPDATE users SET is_online = $2, last_seen = NOW()
		WHERE id = $1
	`, id, isOnline)
	return err
}

// CheckPassword verifies a plaintext password against the stored hash.
func (r *UserRepository) CheckPassword(hash, password string) bool {
	return bcrypt.CompareHashAndPassword([]byte(hash), []byte(password)) == nil
}

// ── Refresh Tokens ────────────────────────────────────────────────────────────

// StoreRefreshToken persists a refresh token JTI.
func (r *UserRepository) StoreRefreshToken(ctx context.Context, userID uuid.UUID, jti string, expiresAt time.Time) error {
	_, err := r.db.Exec(ctx, `
		INSERT INTO refresh_tokens (user_id, jti, expires_at)
		VALUES ($1, $2, $3)
	`, userID, jti, expiresAt)
	return err
}

// GetRefreshToken fetches a refresh token by JTI.
func (r *UserRepository) GetRefreshToken(ctx context.Context, jti string) (*RefreshToken, error) {
	rt := &RefreshToken{}
	err := r.db.QueryRow(ctx, `
		SELECT id, user_id, jti, expires_at, revoked, created_at
		FROM refresh_tokens WHERE jti = $1
	`, jti).Scan(&rt.ID, &rt.UserID, &rt.JTI, &rt.ExpiresAt, &rt.Revoked, &rt.CreatedAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, ErrTokenNotFound
	}
	return rt, err
}

// RevokeRefreshToken marks a token as revoked (logout).
func (r *UserRepository) RevokeRefreshToken(ctx context.Context, jti string) error {
	tag, err := r.db.Exec(ctx, `
		UPDATE refresh_tokens SET revoked = TRUE WHERE jti = $1
	`, jti)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrTokenNotFound
	}
	return nil
}

// CleanExpiredTokens removes expired refresh tokens (call periodically).
func (r *UserRepository) CleanExpiredTokens(ctx context.Context) (int64, error) {
	tag, err := r.db.Exec(ctx, `
		DELETE FROM refresh_tokens WHERE expires_at < NOW() OR revoked = TRUE
	`)
	if err != nil {
		return 0, err
	}
	return tag.RowsAffected(), nil
}

// ── helpers ───────────────────────────────────────────────────────────────────

func isUniqueViolation(err error) bool {
	return err != nil && (fmt.Sprintf("%v", err) == "ERROR: duplicate key value violates unique constraint" ||
		pgErrCode(err) == "23505")
}

func pgErrCode(err error) string {
	type pgErr interface{ SQLState() string }
	if pe, ok := err.(pgErr); ok {
		return pe.SQLState()
	}
	return ""
}

// ── Two-Step Verification ─────────────────────────────────────────────────────

func (r *UserRepository) UpdateTwoStepPIN(ctx context.Context, userID uuid.UUID, hash string) error {
	_, err := r.db.Exec(ctx, `
		UPDATE users SET two_factor_pin_hash = $2 WHERE id = $1
	`, userID, hash)
	return err
}

func (r *UserRepository) GetTwoStepPINHash(ctx context.Context, userID uuid.UUID) (string, error) {
	var hash sql.NullString
	err := r.db.QueryRow(ctx, `
		SELECT two_factor_pin_hash FROM users WHERE id = $1
	`, userID).Scan(&hash)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return "", ErrUserNotFound
		}
		return "", err
	}
	return hash.String, nil
}

// ── E2EE Key Exchange ─────────────────────────────────────────────────────────

type EphemeralPreKey struct {
	KeyID     int32
	PublicKey string
}

func (r *UserRepository) SavePreKeyBundle(ctx context.Context, userID uuid.UUID, identity, signedKey, signature string, oneTimeKeys []*EphemeralPreKey) error {
	tx, err := r.db.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)

	// Save prekey base on user
	_, err = tx.Exec(ctx, `
		UPDATE users SET
			prekey_identity = $2,
			prekey_signed = $3,
			prekey_signature = $4
		WHERE id = $1
	`, userID, identity, signedKey, signature)
	if err != nil {
		return err
	}

	// Delete old one-time keys
	_, err = tx.Exec(ctx, `DELETE FROM user_prekeys WHERE user_id = $1`, userID)
	if err != nil {
		return err
	}

	// Bulk insert new one-time keys
	for _, k := range oneTimeKeys {
		_, err = tx.Exec(ctx, `
			INSERT INTO user_prekeys (user_id, key_id, public_key)
			VALUES ($1, $2, $3)
		`, userID, k.KeyID, k.PublicKey)
		if err != nil {
			return err
		}
	}

	return tx.Commit(ctx)
}

func (r *UserRepository) ConsumePreKeyBundle(ctx context.Context, targetUserID uuid.UUID) (identity, signedKey, signature string, oneTimeKey *EphemeralPreKey, err error) {
	tx, err := r.db.Begin(ctx)
	if err != nil {
		return "", "", "", nil, err
	}
	defer tx.Rollback(ctx)

	// Fetch identity and signed prekey
	var ident, signed, sig sql.NullString
	err = tx.QueryRow(ctx, `
		SELECT prekey_identity, prekey_signed, prekey_signature
		FROM users WHERE id = $1
	`, targetUserID).Scan(&ident, &signed, &sig)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return "", "", "", nil, ErrUserNotFound
		}
		return "", "", "", nil, err
	}

	identity = ident.String
	signedKey = signed.String
	signature = sig.String

	// Retrieve and delete (consume) one ephemeral key
	k := &EphemeralPreKey{}
	err = tx.QueryRow(ctx, `
		SELECT key_id, public_key FROM user_prekeys
		WHERE user_id = $1
		LIMIT 1
	`, targetUserID).Scan(&k.KeyID, &k.PublicKey)
	if err == nil {
		// Key was found, delete it from pool
		_, err = tx.Exec(ctx, `
			DELETE FROM user_prekeys WHERE user_id = $1 AND key_id = $2
		`, targetUserID, k.KeyID)
		if err != nil {
			return "", "", "", nil, err
		}
		oneTimeKey = k
	} else if !errors.Is(err, pgx.ErrNoRows) {
		return "", "", "", nil, err
	}

	err = tx.Commit(ctx)
	return identity, signedKey, signature, oneTimeKey, err
}

// ── Contact Blocking ──────────────────────────────────────────────────────────

func (r *UserRepository) BlockUser(ctx context.Context, blockerID, blockedID uuid.UUID) error {
	_, err := r.db.Exec(ctx, `
		INSERT INTO user_blocks (blocker_id, blocked_id)
		VALUES ($1, $2)
		ON CONFLICT (blocker_id, blocked_id) DO NOTHING
	`, blockerID, blockedID)
	return err
}

func (r *UserRepository) UnblockUser(ctx context.Context, blockerID, blockedID uuid.UUID) error {
	_, err := r.db.Exec(ctx, `
		DELETE FROM user_blocks WHERE blocker_id = $1 AND blocked_id = $2
	`, blockerID, blockedID)
	return err
}

func (r *UserRepository) GetBlockedUsers(ctx context.Context, blockerID uuid.UUID) ([]uuid.UUID, error) {
	rows, err := r.db.Query(ctx, `
		SELECT blocked_id FROM user_blocks WHERE blocker_id = $1
	`, blockerID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var blockedIDs []uuid.UUID
	for rows.Next() {
		var id uuid.UUID
		if err := rows.Scan(&id); err != nil {
			return nil, err
		}
		blockedIDs = append(blockedIDs, id)
	}
	return blockedIDs, nil
}

func (r *UserRepository) IsBlocked(ctx context.Context, blockerID, blockedID uuid.UUID) (bool, error) {
	var exists bool
	err := r.db.QueryRow(ctx, `
		SELECT EXISTS (
			SELECT 1 FROM user_blocks WHERE blocker_id = $1 AND blocked_id = $2
		)
	`, blockerID, blockedID).Scan(&exists)
	return exists, err
}

func (r *UserRepository) RegisterPhone(ctx context.Context, userID uuid.UUID, phone string) error {
	_, err := r.db.Exec(ctx, `
		UPDATE users SET phone = $2, phone_verified = FALSE WHERE id = $1
	`, userID, phone)
	return err
}

func (r *UserRepository) VerifyPhone(ctx context.Context, userID uuid.UUID, phone string) error {
	_, err := r.db.Exec(ctx, `
		UPDATE users SET phone_verified = TRUE WHERE id = $1 AND phone = $2
	`, userID, phone)
	return err
}

func (r *UserRepository) SubscribePushToken(ctx context.Context, userID uuid.UUID, pushToken, platform string) error {
	_, err := r.db.Exec(ctx, `
		INSERT INTO user_push_tokens (user_id, push_token, platform)
		VALUES ($1, $2, $3)
		ON CONFLICT (user_id, push_token) DO UPDATE SET platform = EXCLUDED.platform
	`, userID, pushToken, platform)
	return err
}

