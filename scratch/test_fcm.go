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
	"strings"
	"time"

	"github.com/golang-jwt/jwt/v5"
)

type ServiceAccount struct {
	Type        string `json:"type"`
	ProjectID   string `json:"project_id"`
	PrivateKey  string `json:"private_key"`
	ClientEmail string `json:"client_email"`
	TokenURI    string `json:"token_uri"`
}

func main() {
	keyPath := "services/notification/firebase-service-account.json"
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

	fmt.Printf("Project ID: %s, Client Email: %s\n", sa.ProjectID, sa.ClientEmail)

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
	if resp.StatusCode != http.StatusOK {
		fmt.Printf("Token request failed (%d): %s\n", resp.StatusCode, string(body))
		return
	}

	var tokenResp struct {
		AccessToken string `json:"access_token"`
		ExpiresIn   int    `json:"expires_in"`
	}
	json.Unmarshal(body, &tokenResp)

	fmt.Printf("✅ Google OAuth2 Access Token successfully acquired! Expires in: %d seconds\nToken prefix: %s...\n",
		tokenResp.ExpiresIn, tokenResp.AccessToken[:20])

	// Dry run FCM message send (validate_only: true)
	fcmURL := fmt.Sprintf("https://fcm.googleapis.com/v1/projects/%s/messages:send", sa.ProjectID)
	testPayload := map[string]interface{}{
		"validate_only": true,
		"message": map[string]interface{}{
			"token": "fake_test_token_for_validation_only_1234567890",
			"notification": map[string]string{
				"title": "GoChat Test",
				"body":  "Testing Firebase Cloud Messaging integration",
			},
		},
	}
	testBytes, _ := json.Marshal(testPayload)

	req, _ := http.NewRequestWithContext(context.Background(), "POST", fcmURL, bytes.NewReader(testBytes))
	req.Header.Set("Authorization", "Bearer "+tokenResp.AccessToken)
	req.Header.Set("Content-Type", "application/json")

	fcmResp, err := http.DefaultClient.Do(req)
	if err != nil {
		fmt.Printf("FCM test request failed: %v\n", err)
		return
	}
	defer fcmResp.Body.Close()
	fcmBody, _ := io.ReadAll(fcmResp.Body)

	fmt.Printf("FCM test response status: %d\n", fcmResp.StatusCode)
	if fcmResp.StatusCode == http.StatusOK || strings.Contains(string(fcmBody), "UNREGISTERED") || strings.Contains(string(fcmBody), "INVALID_ARGUMENT") {
		fmt.Printf("✅ FCM v1 API reached and authenticated successfully! Response: %s\n", string(fcmBody))
	} else {
		fmt.Printf("FCM response: %s\n", string(fcmBody))
	}
}
