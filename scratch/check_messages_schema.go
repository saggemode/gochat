package main

import (
	"context"
	"fmt"
	"log"

	"github.com/jackc/pgx/v5"
)

func main() {
	dsn := "postgres://neondb_owner:npg_GfPRWH95xneU@ep-dark-forest-a2188hsu-pooler.eu-central-1.aws.neon.tech/auth-gochat-db?sslmode=require"
	ctx := context.Background()
	conn, err := pgx.Connect(ctx, dsn)
	if err != nil {
		log.Fatalf("connect: %v", err)
	}
	defer conn.Close(ctx)

	rows, err := conn.Query(ctx, "SELECT column_name, data_type, is_nullable FROM information_schema.columns WHERE table_name='messages' ORDER BY ordinal_position")
	if err != nil {
		log.Fatalf("query: %v", err)
	}
	defer rows.Close()

	fmt.Println("Columns in messages table:")
	for rows.Next() {
		var c, t, n string
		if err := rows.Scan(&c, &t, &n); err == nil {
			fmt.Printf("  %s: %s (nullable: %s)\n", c, t, n)
		}
	}
}
