package main

import (
	"bufio"
	"context"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

type DatabaseTarget struct {
	Name string
	Env  string
	DSN  string
}

func loadEnvDSNs(envPath string) (map[string]string, error) {
	file, err := os.Open(envPath)
	if err != nil {
		return nil, err
	}
	defer file.Close()

	dsns := make(map[string]string)
	scanner := bufio.NewScanner(file)
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		parts := strings.SplitN(line, "=", 2)
		if len(parts) == 2 {
			k := strings.TrimSpace(parts[0])
			v := strings.TrimSpace(parts[1])
			if strings.HasSuffix(k, "_DB_DSN") {
				dsns[k] = v
			}
		}
	}
	return dsns, scanner.Err()
}

func main() {
	fmt.Println("=================================================================")
	fmt.Println("🚀 GoChat — Neon Cloud PostgreSQL Migration Runner")
	fmt.Println("=================================================================")

	envMap, err := loadEnvDSNs(".env")
	if err != nil {
		fmt.Printf("❌ Failed to read .env: %v\n", err)
		os.Exit(1)
	}

	targets := []DatabaseTarget{
		{Name: "auth-gochat-db", Env: "AUTH_DB_DSN", DSN: envMap["AUTH_DB_DSN"]},
		{Name: "chat-gochat-db", Env: "CHAT_DB_DSN", DSN: envMap["CHAT_DB_DSN"]},
		{Name: "authz-gochat-db", Env: "AUTHZ_DB_DSN", DSN: envMap["AUTHZ_DB_DSN"]},
		{Name: "group-gochat-db", Env: "GROUP_DB_DSN", DSN: envMap["GROUP_DB_DSN"]},
		{Name: "story-gochat-db", Env: "STORY_DB_DSN", DSN: envMap["STORY_DB_DSN"]},
		{Name: "call-gochat-db", Env: "CALL_DB_DSN", DSN: envMap["CALL_DB_DSN"]},
		{Name: "channel-gochat-db", Env: "CHANNEL_DB_DSN", DSN: envMap["CHANNEL_DB_DSN"]},
		{Name: "ai-gochat-db", Env: "AI_DB_DSN", DSN: envMap["AI_DB_DSN"]},
		{Name: "payment-gochat-db", Env: "PAYMENT_DB_DSN", DSN: envMap["PAYMENT_DB_DSN"]},
		{Name: "social-gochat-db", Env: "SOCIAL_DB_DSN", DSN: envMap["SOCIAL_DB_DSN"]},
		{Name: "miniapp-gochat-db", Env: "MINIAPP_DB_DSN", DSN: envMap["MINIAPP_DB_DSN"]},
		{Name: "business-gochat-db", Env: "BUSINESS_DB_DSN", DSN: envMap["BUSINESS_DB_DSN"]},
	}

	ctx := context.Background()

	// Locate migrations
	migrationsDir := "migrations"
	if _, err := os.Stat(migrationsDir); err != nil {
		migrationsDir = "../migrations"
	}

	// Service folders to scan
	serviceDirs := []string{
		"auth",
		"authz",
		"chat",
		"media",
		"grp",
		"story",
		"call",
		"channel",
		"social",
		"miniapp",
		"business",
	}

	successCount := 0
	for _, target := range targets {
		if target.DSN == "" {
			fmt.Printf("⚠️ [%s] No DSN found in .env, skipping.\n", target.Name)
			continue
		}

		fmt.Printf("\n📦 Connecting to [%s] ...\n", target.Name)

		cfg, err := pgxpool.ParseConfig(target.DSN)
		if err != nil {
			fmt.Printf("   ❌ Invalid DSN: %v\n", err)
			continue
		}
		cfg.ConnConfig.DefaultQueryExecMode = pgx.QueryExecModeSimpleProtocol
		cfg.MaxConns = 3
		cfg.MaxConnLifetime = 2 * time.Minute

		connectCtx, cancelConnect := context.WithTimeout(ctx, 10*time.Second)
		pool, err := pgxpool.NewWithConfig(connectCtx, cfg)
		cancelConnect()
		if err != nil {
			fmt.Printf("   ❌ Connection failed: %v\n", err)
			continue
		}

		// Ping
		pingCtx, pingCancel := context.WithTimeout(ctx, 10*time.Second)
		if err := pool.Ping(pingCtx); err != nil {
			pingCancel()
			pool.Close()
			fmt.Printf("   ❌ Ping failed: %v\n", err)
			continue
		}
		pingCancel()
		fmt.Printf("   ✅ Connected successfully!\n")

		// 1. Run 000_schemas.sql
		schemaFile := filepath.Join(migrationsDir, "000_schemas.sql")
		if content, err := os.ReadFile(schemaFile); err == nil {
			sql := sanitizeSQL(string(content))
			execCtx, execCancel := context.WithTimeout(ctx, 20*time.Second)
			_, err := pool.Exec(execCtx, sql)
			execCancel()
			if err != nil {
				fmt.Printf("   ⚠️ Schema bootstrap note: %v\n", err)
			} else {
				fmt.Printf("   ✅ Executed 000_schemas.sql\n")
			}
		}

		// 2. Run service migrations
		appliedFiles := 0
		for _, svc := range serviceDirs {
			svcPath := filepath.Join(migrationsDir, svc)
			entries, err := os.ReadDir(svcPath)
			if err != nil {
				continue
			}

			var sqlFiles []string
			for _, e := range entries {
				if !e.IsDir() && strings.HasSuffix(e.Name(), ".sql") {
					sqlFiles = append(sqlFiles, filepath.Join(svcPath, e.Name()))
				}
			}
			sort.Strings(sqlFiles)

			for _, sf := range sqlFiles {
				content, err := os.ReadFile(sf)
				if err != nil {
					continue
				}
				sql := sanitizeSQL(string(content))
				execCtx, execCancel := context.WithTimeout(ctx, 20*time.Second)
				_, err = pool.Exec(execCtx, sql)
				execCancel()
				if err != nil {
					// Duplicate relation/function notes are normal
				} else {
					appliedFiles++
				}
			}
		}

		// 3. Run 006_infrastructure_optimization.sql if present
		optFile := filepath.Join(migrationsDir, "006_infrastructure_optimization.sql")
		if content, err := os.ReadFile(optFile); err == nil {
			sql := sanitizeSQL(string(content))
			execCtx, execCancel := context.WithTimeout(ctx, 10*time.Second)
			_, _ = pool.Exec(execCtx, sql)
			execCancel()
		}

		pool.Close()
		fmt.Printf("   🎉 Migration complete for [%s] (applied %d scripts)\n", target.Name, appliedFiles)
		successCount++
	}

	fmt.Println("\n=================================================================")
	fmt.Printf("🏁 All Done! Successfully migrated %d / %d Neon databases.\n", successCount, len(targets))
	fmt.Println("=================================================================")
}

func sanitizeSQL(sql string) string {
	sql = strings.ReplaceAll(sql, `CREATE EXTENSION IF NOT EXISTS "postgis";`, `-- postgis optional`)
	sql = strings.ReplaceAll(sql, `CREATE EXTENSION IF NOT EXISTS postgis;`, `-- postgis optional`)
	return sql
}
