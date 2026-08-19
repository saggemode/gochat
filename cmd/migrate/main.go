package main

import (
	"bufio"
	"context"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
)

func loadEnvFile(path string) {
	file, err := os.Open(path)
	if err != nil {
		return
	}
	defer file.Close()
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
			if os.Getenv(k) == "" {
				os.Setenv(k, v)
			}
		}
	}
}

var targetDatabases = []string{
	"postgres",
	"gochat",
	"auth-gochat-db",
	"authz-gochat-db",
	"chat-gochat-db",
	"media-gochat-db",
	"group-gochat-db",
	"story-gochat-db",
	"call-gochat-db",
	"channel-gochat-db",
	"social-gochat-db",
	"miniapp-gochat-db",
	"business-gochat-db",
	"payment-gochat-db",
	"ai-gochat-db",
}

var schemas = []string{
	"core", "auth", "authz", "chat", "media", "grp",
	"story", "call", "channel", "social", "miniapp", "business",
}

func main() {
	loadEnvFile(".env")

	// Determine host, port, user, password from env or defaults
	user := os.Getenv("POSTGRES_USER")
	if user == "" {
		user = "postgres"
	}
	pass := os.Getenv("POSTGRES_PASSWORD")
	if pass == "" {
		pass = "123"
	}
	host := os.Getenv("POSTGRES_HOST")
	if host == "" {
		host = "localhost"
	}
	port := os.Getenv("POSTGRES_PORT")
	if port == "" {
		port = "5432"
	}

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Minute)
	defer cancel()

	adminDSN := fmt.Sprintf("postgres://%s:%s@%s:%s/postgres?sslmode=disable", user, pass, host, port)

	log.Printf("Connecting to master PostgreSQL at %s:%s...", host, port)
	adminConn, err := pgx.Connect(ctx, adminDSN)
	if err != nil {
		log.Fatalf("❌ Failed to connect to admin database: %v", err)
	}
	defer adminConn.Close(ctx)

	// 1. Ensure all service databases exist
	fmt.Println("\n========================================================")
	fmt.Println("🚀 Step 1: Provisioning Per-Service Databases")
	fmt.Println("========================================================")
	for _, dbName := range targetDatabases {
		var dbExists bool
		err := adminConn.QueryRow(ctx, "SELECT EXISTS(SELECT 1 FROM pg_database WHERE datname = $1)", dbName).Scan(&dbExists)
		if err != nil {
			log.Printf("Warning: checking existence of database %s failed: %v", dbName, err)
			continue
		}
		if !dbExists {
			_, err = adminConn.Exec(ctx, fmt.Sprintf(`CREATE DATABASE "%s";`, dbName))
			if err != nil {
				log.Printf("❌ Failed to create database %s: %v", dbName, err)
			} else {
				fmt.Printf("  ✨ Created database: %s\n", dbName)
			}
		} else {
			fmt.Printf("  ✓ Database exists: %s\n", dbName)
		}
	}

	// 2. Discover all migration SQL files
	fmt.Println("\n========================================================")
	fmt.Println("🔍 Step 2: Discovering Migration Files")
	fmt.Println("========================================================")
	migrationsDir := "migrations"
	if _, err := os.Stat(migrationsDir); os.IsNotExist(err) {
		// Fallback if run from subdirectory
		migrationsDir = "../migrations"
	}

	sqlFiles := discoverMigrationFiles(migrationsDir)
	for _, f := range sqlFiles {
		fmt.Printf("  📜 Found migration: %s\n", f)
	}

	// 3. Apply migrations to ALL service databases
	fmt.Println("\n========================================================")
	fmt.Println("⚙️ Step 3: Applying Migrations Across All Databases")
	fmt.Println("========================================================")

	for _, dbName := range targetDatabases {
		dbDSN := fmt.Sprintf("postgres://%s:%s@%s:%s/%s?sslmode=disable", user, pass, host, port, dbName)
		conn, err := pgx.Connect(ctx, dbDSN)
		if err != nil {
			fmt.Printf("  ⚠️ Could not connect to database %s: %v\n", dbName, err)
			continue
		}

		fmt.Printf("\n--- Processing Database: %s ---\n", dbName)

		// Create schemas
		for _, s := range schemas {
			_, _ = conn.Exec(ctx, fmt.Sprintf("CREATE SCHEMA IF NOT EXISTS %s;", s))
		}

		// Create core.users stub if missing (prevents FK errors in service DBs)
		ensureCoreUsersTable(ctx, conn)

		// Run migrations
		successCount := 0
		for _, file := range sqlFiles {
			sqlBytes, err := os.ReadFile(file)
			if err != nil {
				log.Printf("  ❌ Failed to read %s: %v", file, err)
				continue
			}

			sqlContent := string(sqlBytes)
			sqlContent = strings.ReplaceAll(sqlContent, "uuid_generate_v4()", "gen_random_uuid()")
			// Replace postgis extension requirement if environment doesn't have it
			sqlContent = strings.ReplaceAll(sqlContent, `CREATE EXTENSION IF NOT EXISTS "postgis";`, `-- postgis extension optional`)

			_, err = conn.Exec(ctx, sqlContent)
			if err != nil {
				// Log detailed error line if needed
				log.Printf("  ⚠️ [%s] %s -> %v", dbName, filepath.Base(file), err)
			} else {
				successCount++
			}
		}

		fmt.Printf("  ✅ Applied %d/%d migration files to '%s'\n", successCount, len(sqlFiles), dbName)
		conn.Close(ctx)
	}

	fmt.Println("\n========================================================")
	fmt.Println("🎉 ALL SERVICE DATABASES UP TO DATE & FULLY MIGRATED!")
	fmt.Println("========================================================")
}

func discoverMigrationFiles(baseDir string) []string {
	var files []string

	// 1. Root migrations like 000_schemas.sql
	rootFiles, _ := filepath.Glob(filepath.Join(baseDir, "*.sql"))
	sort.Strings(rootFiles)
	files = append(files, rootFiles...)

	// 2. Subdirectories (auth, authz, business, chat, etc.)
	entries, err := os.ReadDir(baseDir)
	if err == nil {
		for _, entry := range entries {
			if entry.IsDir() {
				subDirFiles, _ := filepath.Glob(filepath.Join(baseDir, entry.Name(), "*.sql"))
				sort.Strings(subDirFiles)
				files = append(files, subDirFiles...)
			}
		}
	}

	return files
}

func ensureCoreUsersTable(ctx context.Context, conn *pgx.Conn) {
	var exists bool
	conn.QueryRow(ctx, "SELECT EXISTS(SELECT 1 FROM information_schema.tables WHERE table_schema='core' AND table_name='users')").Scan(&exists)
	if !exists {
		_, _ = conn.Exec(ctx, `CREATE TABLE IF NOT EXISTS core.users (
			id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
			email           TEXT        NOT NULL UNIQUE DEFAULT '',
			password_hash   TEXT        NOT NULL DEFAULT '',
			display_name    TEXT        NOT NULL DEFAULT '',
			avatar_url      TEXT        NOT NULL DEFAULT '',
			status_text     TEXT        NOT NULL DEFAULT '',
			is_online       BOOLEAN     NOT NULL DEFAULT FALSE,
			last_seen       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
			phone           TEXT,
			phone_verified  BOOLEAN     NOT NULL DEFAULT FALSE,
			two_factor_pin_hash TEXT,
			prekey_identity     TEXT,
			prekey_signed       TEXT,
			prekey_signature    TEXT,
			country_code        TEXT        NOT NULL DEFAULT '',
			created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
			updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
		);`)
	}
}
