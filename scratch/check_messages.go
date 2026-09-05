package main

import (
	"context"
	"fmt"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

func main() {
	dsn := "postgres://neondb_owner:npg_GfPRWH95xneU@ep-dark-forest-a2188hsu-pooler.eu-central-1.aws.neon.tech/chat-gochat-db?sslmode=require"
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	pool, err := pgxpool.New(ctx, dsn)
	if err != nil {
		fmt.Printf("Connection error: %v\n", err)
		return
	}
	defer pool.Close()

	rows, err := pool.Query(ctx, "SELECT id, conversation_id, content, type, media_url, created_at FROM messages ORDER BY created_at DESC LIMIT 10")
	if err != nil {
		fmt.Printf("Query error: %v\n", err)
		return
	}
	defer rows.Close()

	fmt.Println("--- Recent Messages in DB ---")
	for rows.Next() {
		var id, convID, content, msgType, mediaURL string
		var createdAt time.Time
		if err := rows.Scan(&id, &convID, &content, &msgType, &mediaURL, &createdAt); err == nil {
			fmt.Printf("ID: %s | Type: %s | Content: %q | MediaURL len: %d (prefix: %s) | Time: %s\n",
				id, msgType, content, len(mediaURL), mediaURL[:min(30, len(mediaURL))], createdAt.Format(time.RFC3339))
		}
	}
}

func min(a, b int) int {
	if a < b {
		return a
	}
	return b
}
