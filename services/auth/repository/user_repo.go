package repository

import (
	"context"
	"crypto/rand"
	"database/sql"
	"errors"
	"fmt"
	"math/big"
	"strings"
	"time"

	"gochat/pkg/crypto"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
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
	PIN           string
	CountryCode   string
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
	ErrUserNotFound        = errors.New("user not found")
	ErrEmailAlreadyTaken   = errors.New("email already taken")
	ErrPhoneAlreadyTaken   = errors.New("phone number already registered")
	ErrInvalidPassword     = errors.New("invalid password")
	ErrTokenRevoked        = errors.New("refresh token has been revoked")
	ErrTokenNotFound       = errors.New("refresh token not found")
)

// UserRepository handles all user and auth-related DB operations.
type UserRepository struct {
	db        *pgxpool.Pool
	encryptor *crypto.Encryptor
}

// NewUserRepository creates a new repository.
func NewUserRepository(db *pgxpool.Pool) *UserRepository {
	return &UserRepository{db: db}
}

// NewUserRepositoryWithEncryptor creates a new repository with encryption-at-rest for E2EE keys.
func NewUserRepositoryWithEncryptor(db *pgxpool.Pool, enc *crypto.Encryptor) *UserRepository {
	return &UserRepository{db: db, encryptor: enc}
}

// GenerateUniquePIN generates a unique 8-character alphanumeric PIN (A-Z, 0-9)
func (r *UserRepository) GenerateUniquePIN(ctx context.Context) (string, error) {
	const charset = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
	const pinLength = 8
	const maxAttempts = 100

	for attempt := 0; attempt < maxAttempts; attempt++ {
		pin := make([]byte, pinLength)
		for i := 0; i < pinLength; i++ {
			num, err := rand.Int(rand.Reader, big.NewInt(int64(len(charset))))
			if err != nil {
				return "", fmt.Errorf("generating random PIN: %w", err)
			}
			pin[i] = charset[num.Int64()]
		}

		pinStr := string(pin)

		// Check if PIN is unique
		var exists bool
		err := r.db.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM users WHERE pin = $1)`, pinStr).Scan(&exists)
		if err != nil {
			return "", fmt.Errorf("checking PIN uniqueness: %w", err)
		}

		if !exists {
			return pinStr, nil
		}
	}

	return "", fmt.Errorf("failed to generate unique PIN after %d attempts", maxAttempts)
}

// CreateUser inserts a new user with phone, optional email, and optional displayName (auto-generated if empty).
func (r *UserRepository) CreateUser(ctx context.Context, phone, email, password, displayName, countryCode string) (*User, error) {
	hash, err := bcrypt.GenerateFromPassword([]byte(password), bcrypt.DefaultCost)
	if err != nil {
		return nil, fmt.Errorf("hashing password: %w", err)
	}

	// Generate unique PIN
	pin, err := r.GenerateUniquePIN(ctx)
	if err != nil {
		return nil, fmt.Errorf("generating PIN: %w", err)
	}

	if displayName == "" {
		b := make([]byte, 2)
		_, _ = rand.Read(b)
		displayName = fmt.Sprintf("User_%X", b)
	}

	var emailParam interface{}
	if email != "" {
		emailParam = email
	}

	var phoneParam interface{}
	if phone != "" {
		phoneParam = phone
	}

	user := &User{}
	err = r.db.QueryRow(ctx, `
		INSERT INTO users (phone, email, password_hash, display_name, pin, phone_verified, country_code)
		VALUES ($1, $2, $3, $4, $5, $6, $7)
		RETURNING id, COALESCE(email, ''), password_hash, display_name, avatar_url, status_text,
		          is_online, last_seen, created_at, updated_at, COALESCE(phone, ''), phone_verified, pin, country_code
	`, phoneParam, emailParam, string(hash), displayName, pin, phone != "", countryCode).Scan(
		&user.ID, &user.Email, &user.PasswordHash, &user.DisplayName,
		&user.AvatarURL, &user.StatusText, &user.IsOnline,
		&user.LastSeen, &user.CreatedAt, &user.UpdatedAt, &user.Phone, &user.PhoneVerified, &user.PIN, &user.CountryCode,
	)
	if err != nil {
		if isUniqueViolation(err) {
			if phone != "" {
				return nil, ErrPhoneAlreadyTaken
			}
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
		SELECT id, COALESCE(email, ''), password_hash, display_name, avatar_url, status_text,
		       is_online, last_seen, created_at, updated_at, COALESCE(phone, ''), phone_verified, pin, country_code
		FROM users WHERE email = $1
	`, email).Scan(
		&user.ID, &user.Email, &user.PasswordHash, &user.DisplayName,
		&user.AvatarURL, &user.StatusText, &user.IsOnline,
		&user.LastSeen, &user.CreatedAt, &user.UpdatedAt, &user.Phone, &user.PhoneVerified, &user.PIN, &user.CountryCode,
	)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, ErrUserNotFound
	}
	if err != nil {
		return nil, fmt.Errorf("fetching user by email: %w", err)
	}
	return user, nil
}

// GetUserByIdentifier fetches a user by email, phone, PIN, or display_name, returning ErrUserNotFound if missing.
func (r *UserRepository) GetUserByIdentifier(ctx context.Context, identifier string) (*User, error) {
	trimmed := strings.TrimSpace(identifier)
	if trimmed == "" {
		return nil, ErrUserNotFound
	}

	// Extract clean digits for flexible phone number matching
	var digitsBuilder strings.Builder
	for _, ch := range trimmed {
		if ch >= '0' && ch <= '9' {
			digitsBuilder.WriteRune(ch)
		}
	}
	digits := digitsBuilder.String()
	trimmedDigits := strings.TrimLeft(digits, "0")

	phoneWithPlus := trimmed
	if !strings.HasPrefix(phoneWithPlus, "+") && digits != "" {
		phoneWithPlus = "+" + digits
	}

	user := &User{}
	err := r.db.QueryRow(ctx, `
		SELECT id, COALESCE(email, ''), password_hash, display_name, avatar_url, status_text,
		       is_online, last_seen, created_at, updated_at, COALESCE(phone, ''), phone_verified, pin, country_code
		FROM users 
		WHERE LOWER(email) = LOWER($1) 
		   OR phone = $1 
		   OR phone = $2 
		   OR (LENGTH($3) >= 6 AND REGEXP_REPLACE(phone, '[^0-9]', '', 'g') LIKE '%' || $3)
		   OR (LENGTH($4) >= 6 AND REGEXP_REPLACE(phone, '[^0-9]', '', 'g') LIKE '%' || $4)
		   OR (LENGTH($4) >= 6 AND $4 LIKE '%' || REGEXP_REPLACE(phone, '[^0-9]', '', 'g'))
		   OR LOWER(display_name) = LOWER($1) 
		   OR UPPER(pin) = UPPER($1)
		LIMIT 1
	`, trimmed, phoneWithPlus, digits, trimmedDigits).Scan(
		&user.ID, &user.Email, &user.PasswordHash, &user.DisplayName,
		&user.AvatarURL, &user.StatusText, &user.IsOnline,
		&user.LastSeen, &user.CreatedAt, &user.UpdatedAt, &user.Phone, &user.PhoneVerified, &user.PIN, &user.CountryCode,
	)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, ErrUserNotFound
	}
	if err != nil {
		return nil, fmt.Errorf("fetching user by identifier: %w", err)
	}
	return user, nil
}

// GetUserByID fetches a user by primary key.
func (r *UserRepository) GetUserByID(ctx context.Context, id uuid.UUID) (*User, error) {
	user := &User{}
	err := r.db.QueryRow(ctx, `
		SELECT id, COALESCE(email, ''), password_hash, display_name, avatar_url, status_text,
		       is_online, last_seen, created_at, updated_at, COALESCE(phone, ''), phone_verified, pin, country_code
		FROM users WHERE id = $1
	`, id).Scan(
		&user.ID, &user.Email, &user.PasswordHash, &user.DisplayName,
		&user.AvatarURL, &user.StatusText, &user.IsOnline,
		&user.LastSeen, &user.CreatedAt, &user.UpdatedAt, &user.Phone, &user.PhoneVerified, &user.PIN, &user.CountryCode,
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
		SELECT id, COALESCE(email, ''), password_hash, display_name, avatar_url, status_text,
		       is_online, last_seen, created_at, updated_at, COALESCE(phone, ''), phone_verified, pin, country_code
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
			&u.LastSeen, &u.CreatedAt, &u.UpdatedAt, &u.Phone, &u.PhoneVerified, &u.PIN, &u.CountryCode,
		); err != nil {
			return nil, err
		}
		users = append(users, u)
	}
	return users, rows.Err()
}

func (r *UserRepository) GetUsersByIdentifiers(ctx context.Context, identifiers []string) ([]*User, error) {
	var rows pgx.Rows
	var err error
	if len(identifiers) == 0 {
		// Fetch all users for global discovery (capped for safety)
		rows, err = r.db.Query(ctx, `
			SELECT id, COALESCE(email, ''), password_hash, display_name, avatar_url, status_text,
			       is_online, last_seen, created_at, updated_at, COALESCE(phone, ''), phone_verified, pin, country_code
			FROM users ORDER BY created_at DESC LIMIT 200
		`)
	} else {
		rows, err = r.db.Query(ctx, `
			SELECT id, COALESCE(email, ''), password_hash, display_name, avatar_url, status_text,
			       is_online, last_seen, created_at, updated_at, COALESCE(phone, ''), phone_verified, pin, country_code
			FROM users 
			WHERE LOWER(email) = ANY(SELECT LOWER(x) FROM unnest($1::text[]) x)
			   OR phone = ANY($1)
			   OR LOWER(display_name) = ANY(SELECT LOWER(x) FROM unnest($1::text[]) x)
			   OR id::text = ANY($1)
			   OR UPPER(pin) = ANY(SELECT UPPER(x) FROM unnest($1::text[]) x)
		`, identifiers)
	}
	if err != nil {
		return nil, fmt.Errorf("fetching users by identifiers: %w", err)
	}
	defer rows.Close()

	var users []*User
	for rows.Next() {
		u := &User{}
		if err := rows.Scan(
			&u.ID, &u.Email, &u.PasswordHash, &u.DisplayName,
			&u.AvatarURL, &u.StatusText, &u.IsOnline,
			&u.LastSeen, &u.CreatedAt, &u.UpdatedAt, &u.Phone, &u.PhoneVerified, &u.PIN, &u.CountryCode,
		); err != nil {
			return nil, err
		}
		users = append(users, u)
	}
	return users, rows.Err()
}

// UpdateUser updates the editable profile fields.
// Empty strings are ignored (no change).
func (r *UserRepository) UpdateUser(ctx context.Context, id uuid.UUID, displayName, avatarURL, statusText, email, phone string) (*User, error) {
	if email != "" {
		var existingID uuid.UUID
		err := r.db.QueryRow(ctx, `SELECT id FROM users WHERE email = $1 AND id <> $2`, email, id).Scan(&existingID)
		if err == nil {
			return nil, ErrEmailAlreadyTaken
		}
		if err != nil && !errors.Is(err, pgx.ErrNoRows) {
			return nil, fmt.Errorf("checking email availability: %w", err)
		}
	}

	user := &User{}
	err := r.db.QueryRow(ctx, `
		UPDATE users SET
			display_name = CASE WHEN $2 <> '' THEN $2 ELSE display_name END,
			avatar_url   = CASE WHEN $3 = '__REMOVE__' THEN '' WHEN $3 <> '' THEN $3 ELSE avatar_url END,
			status_text  = CASE WHEN $4 <> '' THEN $4 ELSE status_text END,
			email        = CASE WHEN $5 <> '' THEN $5 ELSE email END,
			phone        = CASE WHEN $6 <> '' THEN $6 ELSE phone END
		WHERE id = $1
		RETURNING id, COALESCE(email, ''), password_hash, display_name, avatar_url, status_text,
			          is_online, last_seen, created_at, updated_at, COALESCE(phone, ''), phone_verified, pin, country_code
	`, id, displayName, avatarURL, statusText, email, phone).Scan(
		&user.ID, &user.Email, &user.PasswordHash, &user.DisplayName,
		&user.AvatarURL, &user.StatusText, &user.IsOnline,
		&user.LastSeen, &user.CreatedAt, &user.UpdatedAt, &user.Phone, &user.PhoneVerified, &user.PIN, &user.CountryCode,
	)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, ErrUserNotFound
	}
	if err != nil {
		return nil, fmt.Errorf("updating user: %w", err)
	}
	return user, nil
}

// DeleteUser removes the user record and its auth tokens.
func (r *UserRepository) DeleteUser(ctx context.Context, id uuid.UUID) error {
	_, err := r.db.Exec(ctx, `DELETE FROM refresh_tokens WHERE user_id = $1`, id)
	if err != nil {
		return fmt.Errorf("deleting refresh tokens: %w", err)
	}

	_, err = r.db.Exec(ctx, `DELETE FROM users WHERE id = $1`, id)
	if err != nil {
		return fmt.Errorf("deleting user: %w", err)
	}
	return nil
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
	if err == nil {
		return false
	}
	var pgErr *pgconn.PgError
	if errors.As(err, &pgErr) {
		return pgErr.Code == "23505"
	}
	msg := strings.ToLower(err.Error())
	return strings.Contains(msg, "duplicate key") || strings.Contains(msg, "unique constraint") || strings.Contains(msg, "23505") || pgErrCode(err) == "23505"
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
	if r.encryptor != nil {
		var err error
		identity, err = r.encryptor.Encrypt(identity)
		if err != nil {
			return fmt.Errorf("encrypting identity key: %w", err)
		}
		signedKey, err = r.encryptor.Encrypt(signedKey)
		if err != nil {
			return fmt.Errorf("encrypting signed prekey: %w", err)
		}
		signature, err = r.encryptor.Encrypt(signature)
		if err != nil {
			return fmt.Errorf("encrypting signature: %w", err)
		}
	}

	tx, err := r.db.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)

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

	_, err = tx.Exec(ctx, `DELETE FROM user_prekeys WHERE user_id = $1`, userID)
	if err != nil {
		return err
	}

	for _, k := range oneTimeKeys {
		pkEnc := k.PublicKey
		if r.encryptor != nil {
			pkEnc, err = r.encryptor.Encrypt(pkEnc)
			if err != nil {
				return fmt.Errorf("encrypting one-time key %d: %w", k.KeyID, err)
			}
		}
		_, err = tx.Exec(ctx, `
			INSERT INTO user_prekeys (user_id, key_id, public_key)
			VALUES ($1, $2, $3)
		`, userID, k.KeyID, pkEnc)
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

	if r.encryptor != nil {
		var decErr error
		identity, decErr = r.encryptor.Decrypt(identity)
		if decErr != nil {
			return "", "", "", nil, fmt.Errorf("decrypting identity key: %w", decErr)
		}
		signedKey, decErr = r.encryptor.Decrypt(signedKey)
		if decErr != nil {
			return "", "", "", nil, fmt.Errorf("decrypting signed prekey: %w", decErr)
		}
		signature, decErr = r.encryptor.Decrypt(signature)
		if decErr != nil {
			return "", "", "", nil, fmt.Errorf("decrypting signature: %w", decErr)
		}
	}

	// Retrieve and delete (consume) one ephemeral key
	k := &EphemeralPreKey{}
	err = tx.QueryRow(ctx, `
		SELECT key_id, public_key FROM user_prekeys
		WHERE user_id = $1
		LIMIT 1
	`, targetUserID).Scan(&k.KeyID, &k.PublicKey)
	if err == nil {
		_, err = tx.Exec(ctx, `
			DELETE FROM user_prekeys WHERE user_id = $1 AND key_id = $2
		`, targetUserID, k.KeyID)
		if err != nil {
			return "", "", "", nil, err
		}
		if r.encryptor != nil {
			k.PublicKey, err = r.encryptor.Decrypt(k.PublicKey)
			if err != nil {
				return "", "", "", nil, fmt.Errorf("decrypting one-time key: %w", err)
			}
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

// ── Security & Session Domain Models ───────────────────────────────────────

type UserSession struct {
	ID           uuid.UUID `json:"id"`
	UserID       uuid.UUID `json:"user_id"`
	DeviceName   string    `json:"device_name"`
	OS           string    `json:"os"`
	Browser      string    `json:"browser"`
	IPAddress    string    `json:"ip_address"`
	LastActiveAt time.Time `json:"last_active_at"`
	CreatedAt    time.Time `json:"created_at"`
}

type UserReport struct {
	ID             uuid.UUID `json:"id"`
	ReporterID     uuid.UUID `json:"reporter_id"`
	ReportedUserID uuid.UUID `json:"reported_user_id"`
	Reason         string    `json:"reason"`
	Details        string    `json:"details"`
	EvidenceMsgIDs []string  `json:"evidence_msg_ids"`
	Status         string    `json:"status"`
	CreatedAt      time.Time `json:"created_at"`
}

type SecurityAuditLog struct {
	ID        uuid.UUID `json:"id"`
	UserID    uuid.UUID `json:"user_id"`
	EventType string    `json:"event_type"`
	IPAddress string    `json:"ip_address"`
	UserAgent string    `json:"user_agent"`
	Details   string    `json:"details"`
	CreatedAt time.Time `json:"created_at"`
}

type PrivacySettings struct {
	ProfilePhotoPrivacy string `json:"profile_photo_privacy"`
	StatusPrivacy       string `json:"status_privacy"`
	ReadReceiptsEnabled bool   `json:"read_receipts_enabled"`
	OnlinePrivacy       string `json:"online_privacy"`
	LastSeenPrivacy     string `json:"last_seen_privacy"`
}

// ── Session Management Methods ──────────────────────────────────────────────

func (r *UserRepository) CreateSession(ctx context.Context, userID uuid.UUID, deviceName, os, browser, ipAddress string) (*UserSession, error) {
	if deviceName == "" {
		deviceName = "Desktop Browser"
	}
	if os == "" {
		os = "Windows"
	}
	if browser == "" {
		browser = "Chrome"
	}
	if ipAddress == "" {
		ipAddress = "127.0.0.1"
	}

	sess := &UserSession{}
	err := r.db.QueryRow(ctx, `
		INSERT INTO user_sessions (user_id, device_name, os, browser, ip_address)
		VALUES ($1, $2, $3, $4, $5)
		RETURNING id, user_id, device_name, os, browser, ip_address, last_active_at, created_at
	`, userID, deviceName, os, browser, ipAddress).Scan(
		&sess.ID, &sess.UserID, &sess.DeviceName, &sess.OS, &sess.Browser,
		&sess.IPAddress, &sess.LastActiveAt, &sess.CreatedAt,
	)
	if err != nil {
		return nil, fmt.Errorf("creating session: %w", err)
	}
	return sess, nil
}

func (r *UserRepository) GetUserSessions(ctx context.Context, userID uuid.UUID) ([]*UserSession, error) {
	rows, err := r.db.Query(ctx, `
		SELECT id, user_id, device_name, os, browser, ip_address, last_active_at, created_at
		FROM user_sessions
		WHERE user_id = $1
		ORDER BY last_active_at DESC
	`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var sessions []*UserSession
	for rows.Next() {
		s := &UserSession{}
		if err := rows.Scan(&s.ID, &s.UserID, &s.DeviceName, &s.OS, &s.Browser, &s.IPAddress, &s.LastActiveAt, &s.CreatedAt); err != nil {
			return nil, err
		}
		sessions = append(sessions, s)
	}
	return sessions, nil
}

func (r *UserRepository) TerminateSession(ctx context.Context, sessionID, userID uuid.UUID) error {
	_, err := r.db.Exec(ctx, `
		DELETE FROM user_sessions WHERE id = $1 AND user_id = $2
	`, sessionID, userID)
	return err
}

func (r *UserRepository) TerminateAllOtherSessions(ctx context.Context, userID, currentSessionID uuid.UUID) error {
	_, err := r.db.Exec(ctx, `
		DELETE FROM user_sessions WHERE user_id = $1 AND id != $2
	`, userID, currentSessionID)
	return err
}

// ── Security Audit Logs Methods ─────────────────────────────────────────────

func (r *UserRepository) CreateSecurityAuditLog(ctx context.Context, userID uuid.UUID, eventType, ipAddress, userAgent, details string) (*SecurityAuditLog, error) {
	if ipAddress == "" {
		ipAddress = "127.0.0.1"
	}
	if userAgent == "" {
		userAgent = "GoChat Web Client"
	}

	log := &SecurityAuditLog{}
	err := r.db.QueryRow(ctx, `
		INSERT INTO security_audit_logs (user_id, event_type, ip_address, user_agent, details)
		VALUES ($1, $2, $3, $4, $5)
		RETURNING id, user_id, event_type, ip_address, user_agent, details, created_at
	`, userID, eventType, ipAddress, userAgent, details).Scan(
		&log.ID, &log.UserID, &log.EventType, &log.IPAddress, &log.UserAgent, &log.Details, &log.CreatedAt,
	)
	if err != nil {
		return nil, fmt.Errorf("creating audit log: %w", err)
	}
	return log, nil
}

func (r *UserRepository) GetSecurityAuditLogs(ctx context.Context, userID uuid.UUID) ([]*SecurityAuditLog, error) {
	rows, err := r.db.Query(ctx, `
		SELECT id, user_id, event_type, ip_address, user_agent, details, created_at
		FROM security_audit_logs
		WHERE user_id = $1
		ORDER BY created_at DESC
		LIMIT 50
	`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var logs []*SecurityAuditLog
	for rows.Next() {
		l := &SecurityAuditLog{}
		if err := rows.Scan(&l.ID, &l.UserID, &l.EventType, &l.IPAddress, &l.UserAgent, &l.Details, &l.CreatedAt); err != nil {
			return nil, err
		}
		logs = append(logs, l)
	}
	return logs, nil
}

// ── User Report Method ──────────────────────────────────────────────────────

func (r *UserRepository) CreateUserReport(ctx context.Context, reporterID, reportedUserID uuid.UUID, reason, details string, evidenceMsgIDs []string) (*UserReport, error) {
	report := &UserReport{}
	err := r.db.QueryRow(ctx, `
		INSERT INTO user_reports (reporter_id, reported_user_id, reason, details, evidence_msg_ids)
		VALUES ($1, $2, $3, $4, $5)
		RETURNING id, reporter_id, reported_user_id, reason, details, evidence_msg_ids, status, created_at
	`, reporterID, reportedUserID, reason, details, evidenceMsgIDs).Scan(
		&report.ID, &report.ReporterID, &report.ReportedUserID, &report.Reason,
		&report.Details, &report.EvidenceMsgIDs, &report.Status, &report.CreatedAt,
	)
	if err != nil {
		return nil, fmt.Errorf("creating user report: %w", err)
	}
	return report, nil
}

// ── Account Recovery Methods ────────────────────────────────────────────────

func (r *UserRepository) SetRecoveryEmail(ctx context.Context, userID uuid.UUID, recoveryEmail string) error {
	_, err := r.db.Exec(ctx, `
		UPDATE users SET recovery_email = $2 WHERE id = $1
	`, userID, recoveryEmail)
	return err
}

func (r *UserRepository) RequestAccountRecovery(ctx context.Context, identifier string) (string, error) {
	user, err := r.GetUserByIdentifier(ctx, identifier)
	if err != nil {
		return "", err
	}

	// Generate 6-digit recovery code
	b := make([]byte, 3)
	_, _ = rand.Read(b)
	recoveryCode := fmt.Sprintf("%06d", int(b[0])%1000000)

	hash, err := bcrypt.GenerateFromPassword([]byte(recoveryCode), bcrypt.DefaultCost)
	if err != nil {
		return "", err
	}

	expiresAt := time.Now().Add(15 * time.Minute)

	_, err = r.db.Exec(ctx, `
		UPDATE users 
		SET recovery_code_hash = $2, recovery_expires_at = $3 
		WHERE id = $1
	`, user.ID, string(hash), expiresAt)

	if err != nil {
		return "", err
	}

	return recoveryCode, nil
}

func (r *UserRepository) VerifyAndResetPin(ctx context.Context, identifier, recoveryCode, newPin string) error {
	user, err := r.GetUserByIdentifier(ctx, identifier)
	if err != nil {
		return err
	}

	var hash string
	var expiresAt sql.NullTime
	err = r.db.QueryRow(ctx, `
		SELECT COALESCE(recovery_code_hash, ''), recovery_expires_at 
		FROM users WHERE id = $1
	`, user.ID).Scan(&hash, &expiresAt)
	if err != nil {
		return err
	}

	if hash == "" || !expiresAt.Valid || time.Now().After(expiresAt.Time) {
		return errors.New("recovery code expired or not requested")
	}

	if err := bcrypt.CompareHashAndPassword([]byte(hash), []byte(recoveryCode)); err != nil {
		return errors.New("invalid recovery code")
	}

	// Code verified — update PIN and clear recovery code
	_, err = r.db.Exec(ctx, `
		UPDATE users 
		SET pin = $2, recovery_code_hash = '', recovery_expires_at = NULL 
		WHERE id = $1
	`, user.ID, newPin)

	return err
}

// ── Granular Privacy Settings Methods ───────────────────────────────────────

func (r *UserRepository) UpdatePrivacySettings(ctx context.Context, userID uuid.UUID, profilePhoto, statusPrivacy string, readReceipts bool, onlinePrivacy string) error {
	_, err := r.db.Exec(ctx, `
		UPDATE users 
		SET profile_photo_privacy = $2,
		    status_privacy = $3,
		    read_receipts_enabled = $4,
		    online_privacy = $5
		WHERE id = $1
	`, userID, profilePhoto, statusPrivacy, readReceipts, onlinePrivacy)
	return err
}

func (r *UserRepository) GetPrivacySettings(ctx context.Context, userID uuid.UUID) (*PrivacySettings, error) {
	ps := &PrivacySettings{}
	err := r.db.QueryRow(ctx, `
		SELECT COALESCE(profile_photo_privacy, 'everyone'),
		       COALESCE(status_privacy, 'everyone'),
		       COALESCE(read_receipts_enabled, true),
		       COALESCE(online_privacy, 'everyone')
		FROM users WHERE id = $1
	`, userID).Scan(&ps.ProfilePhotoPrivacy, &ps.StatusPrivacy, &ps.ReadReceiptsEnabled, &ps.OnlinePrivacy)
	if err != nil {
		return nil, err
	}
	return ps, nil
}
