package main

import (
	"context"
	"fmt"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

func main() {
	dsn := "postgres://neondb_owner:npg_GfPRWH95xneU@ep-dark-forest-a2188hsu-pooler.eu-central-1.aws.neon.tech/story-gochat-db?sslmode=require"
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	pool, err := pgxpool.New(ctx, dsn)
	if err != nil {
		fmt.Printf("Connection error: %v\n", err)
		return
	}
	defer pool.Close()

	rows, err := pool.Query(ctx, "SELECT id, user_id, media_url, media_type, content, created_at FROM stories ORDER BY created_at DESC LIMIT 5")
	if err != nil {
		fmt.Printf("Query error: %v\n", err)
		return
	}
	defer rows.Close()

	fmt.Println("--- Recent Stories in DB ---")
	for rows.Next() {
		var id, uid, mediaURL, mediaType, content string
		var createdAt time.Time
		if err := rows.Scan(&id, &uid, &mediaURL, &mediaType, &content, &createdAt); err == nil {
			fmt.Printf("ID: %s | User: %s | Type: %s | MediaURL len: %d (prefix: %s) | Time: %s\n",
				id, uid, mediaType, len(mediaURL), mediaURL[:min(30, len(mediaURL))], createdAt.Format(time.RFC3339))
		}
	}
}

func min(a, b int) int {
	if a < b {
		return a
	}
	return b
}
