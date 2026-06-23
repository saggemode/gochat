package repository

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
)

type MiniAppRepository struct {
	db *pgxpool.Pool
}

func NewMiniAppRepository(db *pgxpool.Pool) *MiniAppRepository {
	return &MiniAppRepository{db: db}
}

// ── Bots ────────────────────────────────────────────────────────────────────

type Bot struct {
	ID          string
	OwnerID     string
	Username    string
	DisplayName string
	Description string
	AvatarURL   string
	WebhookURL  string
	IsActive    bool
	IsVerified  bool
	Commands    []*BotCommand
}

type BotCommand struct {
	Command     string
	Description string
}

func (r *MiniAppRepository) RegisterBot(ctx context.Context, ownerID, username, displayName, desc, webhookURL string, cmds []*BotCommand) (*Bot, error) {
	id := uuid.New().String()
	_, err := r.db.Exec(ctx,
		`INSERT INTO bots (id, owner_id, username, display_name, description, webhook_url)
		 VALUES ($1, $2, $3, $4, $5, $6)`,
		id, ownerID, username, displayName, desc, webhookURL)
	if err != nil {
		return nil, fmt.Errorf("register bot: %w", err)
	}

	for _, cmd := range cmds {
		cmdID := uuid.New().String()
		r.db.Exec(ctx,
			`INSERT INTO bot_commands (id, bot_id, command, description) VALUES ($1, $2, $3, $4)`,
			cmdID, id, cmd.Command, cmd.Description)
	}

	return &Bot{ID: id, OwnerID: ownerID, Username: username, DisplayName: displayName,
		Description: desc, WebhookURL: webhookURL, IsActive: true, Commands: cmds}, nil
}

func (r *MiniAppRepository) ListBots(ctx context.Context, limit, offset int) ([]*Bot, int, error) {
	var total int
	r.db.QueryRow(ctx, `SELECT COUNT(*) FROM bots WHERE is_active = TRUE`).Scan(&total)

	rows, err := r.db.Query(ctx,
		`SELECT id, owner_id, username, display_name, COALESCE(description,''), COALESCE(avatar_url,''), is_active, is_verified
		 FROM bots WHERE is_active = TRUE ORDER BY username LIMIT $1 OFFSET $2`, limit, offset)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()

	var bots []*Bot
	for rows.Next() {
		b := &Bot{}
		rows.Scan(&b.ID, &b.OwnerID, &b.Username, &b.DisplayName, &b.Description, &b.AvatarURL, &b.IsActive, &b.IsVerified)
		bots = append(bots, b)
	}
	return bots, total, nil
}

// ── Mini-Apps ───────────────────────────────────────────────────────────────

type MiniApp struct {
	ID           string
	DeveloperID  string
	Name         string
	Description  string
	IconURL      string
	ManifestURL  string
	Category     string
	IsApproved   bool
	InstallCount int
}

func (r *MiniAppRepository) RegisterMiniApp(ctx context.Context, devID, name, desc, iconURL, manifestURL, category string) (*MiniApp, error) {
	id := uuid.New().String()
	_, err := r.db.Exec(ctx,
		`INSERT INTO miniapps (id, developer_id, name, description, icon_url, manifest_url, category)
		 VALUES ($1, $2, $3, $4, $5, $6, $7)`,
		id, devID, name, desc, iconURL, manifestURL, category)
	if err != nil {
		return nil, fmt.Errorf("register miniapp: %w", err)
	}
	return &MiniApp{ID: id, DeveloperID: devID, Name: name, Description: desc,
		IconURL: iconURL, ManifestURL: manifestURL, Category: category}, nil
}

func (r *MiniAppRepository) LaunchMiniApp(ctx context.Context, userID, miniappID, convID string) (string, string, error) {
	sessionID := uuid.New().String()
	_, err := r.db.Exec(ctx,
		`INSERT INTO miniapp_sessions (id, miniapp_id, user_id, conversation_id) VALUES ($1, $2, $3, $4)`,
		sessionID, miniappID, userID, convID)
	if err != nil {
		return "", "", err
	}
	var manifestURL string
	r.db.QueryRow(ctx, `SELECT manifest_url FROM miniapps WHERE id = $1`, miniappID).Scan(&manifestURL)
	return sessionID, manifestURL, nil
}

func (r *MiniAppRepository) ListMiniApps(ctx context.Context, limit, offset int, category string) ([]*MiniApp, int, error) {
	var total int
	query := `SELECT COUNT(*) FROM miniapps WHERE is_approved = TRUE`
	args := []interface{}{}
	argIdx := 1
	if category != "" {
		query += fmt.Sprintf(` AND category = $%d`, argIdx)
		args = append(args, category)
		argIdx++
	}
	r.db.QueryRow(ctx, query, args...).Scan(&total)

	selectQ := `SELECT id, developer_id, name, COALESCE(description,''), COALESCE(icon_url,''),
		manifest_url, COALESCE(category,''), is_approved, install_count
		FROM miniapps WHERE is_approved = TRUE`
	selectArgs := []interface{}{}
	selectArgIdx := 1
	if category != "" {
		selectQ += fmt.Sprintf(` AND category = $%d`, selectArgIdx)
		selectArgs = append(selectArgs, category)
		selectArgIdx++
	}
	selectQ += fmt.Sprintf(` ORDER BY install_count DESC LIMIT $%d OFFSET $%d`, selectArgIdx, selectArgIdx+1)
	selectArgs = append(selectArgs, limit, offset)

	rows, err := r.db.Query(ctx, selectQ, selectArgs...)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()

	var apps []*MiniApp
	for rows.Next() {
		a := &MiniApp{}
		rows.Scan(&a.ID, &a.DeveloperID, &a.Name, &a.Description, &a.IconURL,
			&a.ManifestURL, &a.Category, &a.IsApproved, &a.InstallCount)
		apps = append(apps, a)
	}
	return apps, total, nil
}

// ── Webhooks ────────────────────────────────────────────────────────────────

type Webhook struct {
	ID        string
	UserID    string
	URL       string
	Events    []string
	Secret    string
	IsActive  bool
	CreatedAt time.Time
}

func (r *MiniAppRepository) RegisterWebhook(ctx context.Context, userID, url string, events []string) (*Webhook, string, error) {
	id := uuid.New().String()
	secret := generateSecret()
	_, err := r.db.Exec(ctx,
		`INSERT INTO webhooks (id, user_id, url, events, secret) VALUES ($1, $2, $3, $4, $5)`,
		id, userID, url, events, secret)
	if err != nil {
		return nil, "", err
	}
	return &Webhook{ID: id, UserID: userID, URL: url, Events: events, IsActive: true, CreatedAt: time.Now()}, secret, nil
}

func (r *MiniAppRepository) ListWebhooks(ctx context.Context, userID string) ([]*Webhook, error) {
	rows, err := r.db.Query(ctx,
		`SELECT id, url, is_active, created_at FROM webhooks WHERE user_id = $1 AND is_active = TRUE`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var webhooks []*Webhook
	for rows.Next() {
		w := &Webhook{UserID: userID}
		rows.Scan(&w.ID, &w.URL, &w.IsActive, &w.CreatedAt)
		webhooks = append(webhooks, w)
	}
	return webhooks, nil
}

func (r *MiniAppRepository) DeleteWebhook(ctx context.Context, userID, webhookID string) error {
	_, err := r.db.Exec(ctx,
		`UPDATE webhooks SET is_active = FALSE WHERE id = $1 AND user_id = $2`, webhookID, userID)
	return err
}

// ── API Keys ────────────────────────────────────────────────────────────────

type APIKey struct {
	ID          string
	Name        string
	KeyPrefix   string
	Permissions []string
	IsActive    bool
}

func (r *MiniAppRepository) CreateAPIKey(ctx context.Context, userID, name string, permissions []string) (*APIKey, string, error) {
	id := uuid.New().String()
	rawKey := generateSecret()
	keyHash := hashKey(rawKey)
	prefix := rawKey[:8]

	_, err := r.db.Exec(ctx,
		`INSERT INTO api_keys (id, user_id, key_hash, name, permissions) VALUES ($1, $2, $3, $4, $5)`,
		id, userID, keyHash, name, permissions)
	if err != nil {
		return nil, "", err
	}
	return &APIKey{ID: id, Name: name, KeyPrefix: prefix, Permissions: permissions, IsActive: true}, rawKey, nil
}

func (r *MiniAppRepository) ListAPIKeys(ctx context.Context, userID string) ([]*APIKey, error) {
	rows, err := r.db.Query(ctx,
		`SELECT id, name, LEFT(key_hash, 8), is_active FROM api_keys WHERE user_id = $1 AND is_active = TRUE`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var keys []*APIKey
	for rows.Next() {
		k := &APIKey{}
		rows.Scan(&k.ID, &k.Name, &k.KeyPrefix, &k.IsActive)
		keys = append(keys, k)
	}
	return keys, nil
}

func (r *MiniAppRepository) RevokeAPIKey(ctx context.Context, userID, keyID string) error {
	_, err := r.db.Exec(ctx,
		`UPDATE api_keys SET is_active = FALSE WHERE id = $1 AND user_id = $2`, keyID, userID)
	return err
}

func generateSecret() string {
	b := make([]byte, 32)
	rand.Read(b)
	return hex.EncodeToString(b)
}

func hashKey(key string) string {
	h := sha256.Sum256([]byte(key))
	return hex.EncodeToString(h[:])
}
