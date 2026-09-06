//go:build ignore

package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

type ServiceAccount struct {
	Type        string `json:"type"`
	ProjectID   string `json:"project_id"`
	PrivateKey  string `json:"private_key"`
	ClientEmail string `json:"client_email"`
	TokenURI    string `json:"token_uri"`
}

func main() {
	keyPath := "config/firebase-service-account.json"
	data, err := os.ReadFile(keyPath)
	if err != nil {
		fmt.Printf("Failed to read file: %v\n", err)
		return
	}

	var sa ServiceAccount
	if err := json.Unmarshal(data, &sa); err != nil {
		fmt.Printf("Failed to parse JSON: %v\n", err)
		return
	}

	rsaKey, err := jwt.ParseRSAPrivateKeyFromPEM([]byte(sa.PrivateKey))
	if err != nil {
		fmt.Printf("Failed to parse RSA key: %v\n", err)
		return
	}

	now := time.Now()
	token := jwt.NewWithClaims(jwt.SigningMethodRS256, jwt.MapClaims{
		"iss":   sa.ClientEmail,
		"scope": "https://www.googleapis.com/auth/firebase.messaging",
		"aud":   "https://oauth2.googleapis.com/token",
		"iat":   now.Unix(),
		"exp":   now.Add(time.Hour).Unix(),
	})

	signedJWT, err := token.SignedString(rsaKey)
	if err != nil {
		fmt.Printf("Failed to sign JWT: %v\n", err)
		return
	}

	tokenURL := sa.TokenURI
	if tokenURL == "" {
		tokenURL = "https://oauth2.googleapis.com/token"
	}

	resp, err := http.PostForm(tokenURL, url.Values{
		"grant_type": {"urn:ietf:params:oauth:grant-type:jwt-bearer"},
		"assertion":  {signedJWT},
	})
	if err != nil {
		fmt.Printf("Failed to request access token: %v\n", err)
		return
	}
	defer resp.Body.Close()

	body, _ := io.ReadAll(resp.Body)
	var tokenResp struct {
		AccessToken string `json:"access_token"`
	}
	json.Unmarshal(body, &tokenResp)

	// Fetch the latest token from DB
	dsn := "postgres://neondb_owner:npg_GfPRWH95xneU@ep-dark-forest-a2188hsu-pooler.eu-central-1.aws.neon.tech/auth-gochat-db?sslmode=require"
	pool, err := pgxpool.New(context.Background(), dsn)
	if err != nil {
		fmt.Printf("DB error: %v\n", err)
		return
	}
	defer pool.Close()

	var pushToken, uid string
	err = pool.QueryRow(context.Background(), "SELECT user_id, push_token FROM auth.user_push_tokens ORDER BY created_at DESC LIMIT 1").Scan(&uid, &pushToken)
	if err != nil {
		fmt.Printf("Token query error: %v\n", err)
		return
	}

	fmt.Printf("Targeting latest registered device for user: %s\n", uid)

	fcmURL := fmt.Sprintf("https://fcm.googleapis.com/v1/projects/%s/messages:send", sa.ProjectID)
	fcmPayload := map[string]interface{}{
		"message": map[string]interface{}{
			"token": pushToken,
			"notification": map[string]string{
				"title": "GoChat Push Active 🎉",
				"body":  "Firebase Cloud Messaging is now fully connected!",
			},
			"data": map[string]string{
				"type":            "chat_message",
				"conversation_id": "test_conv",
				"sender_id":       "system",
				"sender_name":     "GoChat System",
			},
			"android": map[string]interface{}{
				"priority": "high",
				"notification": map[string]interface{}{
					"channel_id": "gochat_channel_messages",
					"sound":      "default",
				},
			},
		},
	}
	fcmBytes, _ := json.Marshal(fcmPayload)

	req, _ := http.NewRequestWithContext(context.Background(), "POST", fcmURL, bytes.NewReader(fcmBytes))
	req.Header.Set("Authorization", "Bearer "+tokenResp.AccessToken)
	req.Header.Set("Content-Type", "application/json")

	fcmResp, err := http.DefaultClient.Do(req)
	if err != nil {
		fmt.Printf("FCM send error: %v\n", err)
		return
	}
	defer fcmResp.Body.Close()

	respBody, _ := io.ReadAll(fcmResp.Body)
	fmt.Printf("FCM Response Status: %d\n", fcmResp.StatusCode)
	fmt.Printf("FCM Response Body: %s\n", string(respBody))
}
