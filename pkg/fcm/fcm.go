package fcm

import (
	"bytes"
	"context"
	"crypto/rsa"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

type serviceAccountJSON struct {
	Type        string `json:"type"`
	ProjectID   string `json:"project_id"`
	PrivateKey  string `json:"private_key"`
	ClientEmail string `json:"client_email"`
	TokenURI    string `json:"token_uri"`
}

// Client is a production-grade Firebase Cloud Messaging (FCM v1) client.
type Client struct {
	projectID   string
	clientEmail string
	rsaKey      *rsa.PrivateKey
	tokenURI    string

	tokenMu        sync.RWMutex
	accessToken    string
	tokenExpiresAt time.Time

	httpClient *http.Client
}

var (
	defaultClientInstance *Client
	defaultClientOnce     sync.Once
)

// GetDefaultClient returns the singleton FCM client, searching known credential paths.
func GetDefaultClient() *Client {
	defaultClientOnce.Do(func() {
		candidatePaths := []string{
			os.Getenv("FIREBASE_SERVICE_ACCOUNT_FILE"),
			"config/firebase-service-account.json",
			"services/notification/firebase-service-account.json",
			"firebase-service-account.json",
			"../config/firebase-service-account.json",
			"../../config/firebase-service-account.json",
		}

		rawEnv := os.Getenv("FIREBASE_CREDENTIALS_JSON")
		if rawEnv != "" {
			c, err := NewClient(rawEnv)
			if err == nil {
				defaultClientInstance = c
				return
			}
		}

		for _, p := range candidatePaths {
			if p == "" {
				continue
			}
			if _, err := os.Stat(p); err == nil {
				c, err := NewClient(p)
				if err == nil {
					defaultClientInstance = c
					return
				}
			}
		}
	})
	return defaultClientInstance
}

// NewClient initializes an FCM client from a file path or raw JSON string.
func NewClient(pathOrJSON string) (*Client, error) {
	var data []byte
	trimmed := strings.TrimSpace(pathOrJSON)
	if strings.HasPrefix(trimmed, "{") {
		data = []byte(trimmed)
	} else {
		var err error
		data, err = os.ReadFile(pathOrJSON)
		if err != nil {
			return nil, fmt.Errorf("failed to read firebase service account file: %w", err)
		}
	}

	var sa serviceAccountJSON
	if err := json.Unmarshal(data, &sa); err != nil {
		return nil, fmt.Errorf("invalid firebase service account json: %w", err)
	}

	if sa.ProjectID == "" || sa.PrivateKey == "" || sa.ClientEmail == "" {
		return nil, errors.New("firebase credentials missing project_id, private_key, or client_email")
	}

	rsaKey, err := jwt.ParseRSAPrivateKeyFromPEM([]byte(sa.PrivateKey))
	if err != nil {
		return nil, fmt.Errorf("failed to parse RSA private key: %w", err)
	}

	tokenURI := sa.TokenURI
	if tokenURI == "" {
		tokenURI = "https://oauth2.googleapis.com/token"
	}

	return &Client{
		projectID:   sa.ProjectID,
		clientEmail: sa.ClientEmail,
		rsaKey:      rsaKey,
		tokenURI:    tokenURI,
		httpClient: &http.Client{
			Timeout: 10 * time.Second,
		},
	}, nil
}

// GetAccessToken returns a valid Google OAuth2 Bearer token, auto-refreshing when needed.
func (c *Client) GetAccessToken(ctx context.Context) (string, error) {
	c.tokenMu.RLock()
	if c.accessToken != "" && time.Now().Before(c.tokenExpiresAt.Add(-5*time.Minute)) {
		token := c.accessToken
		c.tokenMu.RUnlock()
		return token, nil
	}
	c.tokenMu.RUnlock()

	c.tokenMu.Lock()
	defer c.tokenMu.Unlock()

	// Double check after acquiring write lock
	if c.accessToken != "" && time.Now().Before(c.tokenExpiresAt.Add(-5*time.Minute)) {
		return c.accessToken, nil
	}

	now := time.Now()
	jwtToken := jwt.NewWithClaims(jwt.SigningMethodRS256, jwt.MapClaims{
		"iss":   c.clientEmail,
		"scope": "https://www.googleapis.com/auth/firebase.messaging",
		"aud":   c.tokenURI,
		"iat":   now.Unix(),
		"exp":   now.Add(time.Hour).Unix(),
	})

	signedJWT, err := jwtToken.SignedString(c.rsaKey)
	if err != nil {
		return "", fmt.Errorf("failed to sign google jwt assertion: %w", err)
	}

	reqBody := url.Values{
		"grant_type": {"urn:ietf:params:oauth:grant-type:jwt-bearer"},
		"assertion":  {signedJWT},
	}

	httpReq, err := http.NewRequestWithContext(ctx, "POST", c.tokenURI, strings.NewReader(reqBody.Encode()))
	if err != nil {
		return "", err
	}
	httpReq.Header.Set("Content-Type", "application/x-www-form-urlencoded")

	httpResp, err := c.httpClient.Do(httpReq)
	if err != nil {
		return "", fmt.Errorf("oauth2 token request failed: %w", err)
	}
	defer httpResp.Body.Close()

	respBytes, _ := io.ReadAll(httpResp.Body)
	if httpResp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("oauth2 token error (%d): %s", httpResp.StatusCode, string(respBytes))
	}

	var tokenResp struct {
		AccessToken string `json:"access_token"`
		ExpiresIn   int    `json:"expires_in"`
	}
	if err := json.Unmarshal(respBytes, &tokenResp); err != nil {
		return "", fmt.Errorf("failed to decode oauth2 token response: %w", err)
	}

	c.accessToken = tokenResp.AccessToken
	c.tokenExpiresAt = now.Add(time.Duration(tokenResp.ExpiresIn) * time.Second)

	return c.accessToken, nil
}

// SendNotification dispatches an FCM v1 push notification to a device registration token.
func (c *Client) SendNotification(ctx context.Context, pushToken, title, body string, data map[string]string) error {
	if pushToken == "" {
		return errors.New("empty push token")
	}

	accessToken, err := c.GetAccessToken(ctx)
	if err != nil {
		return fmt.Errorf("failed to obtain fcm oauth access token: %w", err)
	}

	fcmURL := fmt.Sprintf("https://fcm.googleapis.com/v1/projects/%s/messages:send", c.projectID)

	dataPayload := make(map[string]string)
	for k, v := range data {
		dataPayload[k] = v
	}
	if dataPayload["title"] == "" {
		dataPayload["title"] = title
	}
	if dataPayload["body"] == "" {
		dataPayload["body"] = body
	}

	// High-priority data message ensures onMessageReceived is invoked in all Android states
	// (foreground, background, terminated), enabling offline Room DB caching, custom circular avatars,
	// active-chat suppression, and Direct Reply RemoteInput actions.
	messageMap := map[string]interface{}{
		"token": pushToken,
		"data":  dataPayload,
		"android": map[string]interface{}{
			"priority": "high",
		},
	}

	// For iOS / Web platforms, include the notification block for native system tray rendering
	if p := strings.ToLower(dataPayload["platform"]); p == "ios" || p == "web" || p == "apns" {
		messageMap["notification"] = map[string]string{
			"title": title,
			"body":  body,
		}
	}

	payload := map[string]interface{}{
		"message": messageMap,
	}

	payloadBytes, err := json.Marshal(payload)
	if err != nil {
		return err
	}

	req, err := http.NewRequestWithContext(ctx, "POST", fcmURL, bytes.NewReader(payloadBytes))
	if err != nil {
		return err
	}
	req.Header.Set("Authorization", "Bearer "+accessToken)
	req.Header.Set("Content-Type", "application/json")

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return fmt.Errorf("fcm dispatch request failed: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		respBody, _ := io.ReadAll(resp.Body)
		return fmt.Errorf("fcm api rejected message (%d): %s", resp.StatusCode, string(respBody))
	}

	return nil
}

// SendMulticast sends a push notification to multiple device tokens concurrently.
func (c *Client) SendMulticast(ctx context.Context, pushTokens []string, title, body string, data map[string]string) int {
	if len(pushTokens) == 0 {
		return 0
	}

	var wg sync.WaitGroup
	successCount := 0
	var countMu sync.Mutex

	for _, token := range pushTokens {
		t := token
		if t == "" {
			continue
		}
		wg.Add(1)
		go func() {
			defer wg.Done()
			err := c.SendNotification(ctx, t, title, body, data)
			if err == nil {
				countMu.Lock()
				successCount++
				countMu.Unlock()
			}
		}()
	}

	wg.Wait()
	return successCount
}

const defaultAuthDSN = "postgres://neondb_owner:npg_GfPRWH95xneU@ep-dark-forest-a2188hsu-pooler.eu-central-1.aws.neon.tech/auth-gochat-db?sslmode=require"

var (
	dbPoolInstance *pgxpool.Pool
	dbPoolOnce     sync.Once
)

func getDBPool() *pgxpool.Pool {
	dbPoolOnce.Do(func() {
		dsn := os.Getenv("AUTH_DB_DSN")
		if dsn == "" {
			dsn = os.Getenv("POSTGRES_DSN")
		}
		if dsn == "" || strings.Contains(dsn, "localhost:5432") {
			dsn = defaultAuthDSN
		}
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		pool, err := pgxpool.New(ctx, dsn)
		if err == nil {
			dbPoolInstance = pool
		}
	})
	return dbPoolInstance
}

// GetPushTokensForUser queries all registered FCM tokens for a user ID.
func GetPushTokensForUser(ctx context.Context, userID string) ([]string, error) {
	pool := getDBPool()
	if pool == nil {
		return nil, errors.New("database pool unavailable")
	}

	rows, err := pool.Query(ctx, "SELECT push_token FROM auth.user_push_tokens WHERE user_id = $1", userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var tokens []string
	for rows.Next() {
		var t string
		if err := rows.Scan(&t); err == nil && t != "" {
			tokens = append(tokens, t)
		}
	}
	return tokens, nil
}

// SendToUser dispatches a push notification to all devices registered for a user.
func SendToUser(ctx context.Context, userID, title, body string, data map[string]string) error {
	client := GetDefaultClient()
	if client == nil {
		return errors.New("fcm client not initialized")
	}

	tokens, err := GetPushTokensForUser(ctx, userID)
	if err != nil || len(tokens) == 0 {
		return nil
	}

	client.SendMulticast(ctx, tokens, title, body, data)
	return nil
}

