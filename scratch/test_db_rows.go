//go:build ignore

package main

import (
	"context"
	"fmt"
	"os"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

func main() {
	dsn := os.Getenv("AUTH_DB_DSN")
	if dsn == "" {
		dsn = "postgres://neondb_owner:npg_GfPRWH95xneU@ep-dark-forest-a2188hsu-pooler.eu-central-1.aws.neon.tech/auth-gochat-db?sslmode=require"
	}

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	pool, err := pgxpool.New(ctx, dsn)
	if err != nil {
		fmt.Printf("Connection error: %v\n", err)
		return
	}
	defer pool.Close()

	rows, err := pool.Query(ctx, "SELECT user_id, push_token, platform, created_at FROM auth.user_push_tokens")
	if err != nil {
		fmt.Printf("Query error: %v\n", err)
		return
	}
	defer rows.Close()

	for rows.Next() {
		var uid, token, platform string
		var createdAt time.Time
		if err := rows.Scan(&uid, &token, &platform, &createdAt); err == nil {
			fmt.Printf("User: %s, Platform: %s, Token prefix: %s..., Created: %s\n",
				uid, platform, token[:min(20, len(token))], createdAt.Format(time.RFC3339))
		}
	}
}

func min(a, b int) int {
	if a < b {
		return a
	}
	return b
}
