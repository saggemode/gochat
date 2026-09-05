package main

import (
	"context"
	"fmt"
	"time"

	"gochat/pkg/fcm"

	"github.com/jackc/pgx/v5/pgxpool"
)

func main() {
	client := fcm.GetDefaultClient()
	if client == nil {
		fmt.Println("❌ FCM Default Client failed to initialize")
		return
	}
	fmt.Println("✅ FCM Default Client initialized successfully!")

	dsn := "postgres://neondb_owner:npg_GfPRWH95xneU@ep-dark-forest-a2188hsu-pooler.eu-central-1.aws.neon.tech/auth-gochat-db?sslmode=require"
	pool, err := pgxpool.New(context.Background(), dsn)
	if err != nil {
		fmt.Printf("DB error: %v\n", err)
		return
	}
	defer pool.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	var uid, token string
	err = pool.QueryRow(ctx, "SELECT user_id, push_token FROM auth.user_push_tokens ORDER BY created_at DESC LIMIT 1").Scan(&uid, &token)
	if err != nil {
		fmt.Printf("Failed to fetch token: %v\n", err)
		return
	}

	fmt.Printf("Found user: %s, token: %s...\n", uid, token[:20])

	err = client.SendNotification(ctx, token, "Test from pkg/fcm", "Push notifications are working in GoChat!", map[string]string{
		"conversation_id": "test-conv-id",
		"sender_name":     "GoChat Server",
		"sender_id":       "system",
		"type":            "chat_message",
	})
	if err != nil {
		fmt.Printf("❌ FCM send failed: %v\n", err)
		return
	}

	fmt.Println("🎉 Notification sent and acknowledged by Google FCM successfully!")
}
