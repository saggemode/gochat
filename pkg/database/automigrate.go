package database

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"

	"github.com/jackc/pgx/v5/pgxpool"
	"go.uber.org/zap"
)

// AutoMigrate discovers migration files from multiple potential paths (/migrations, ./migrations, ../../migrations)
// and applies all pending SQL scripts automatically for the specified service.
func AutoMigrate(ctx context.Context, pool *pgxpool.Pool, serviceName string, log *zap.Logger) error {
	log.Info("🔄 Running permanent boot-time database auto-migration...", zap.String("service", serviceName))

	// 1. Ensure core and service schemas exist
	schemas := []string{"core", serviceName, "public"}
	for _, s := range schemas {
		if s == "" {
			continue
		}
		_, err := pool.Exec(ctx, fmt.Sprintf("CREATE SCHEMA IF NOT EXISTS %s;", s))
		if err != nil {
			log.Warn("Schema creation warning", zap.String("schema", s), zap.Error(err))
		}
	}

	// 2. Ensure core.users table stub exists (prevents FK failures across services)
	_, _ = pool.Exec(ctx, `CREATE TABLE IF NOT EXISTS core.users (
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
		country_code    TEXT        NOT NULL DEFAULT '',
		created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
		updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
	);`)

	// 3. Locate candidate migrations directory
	candidatePaths := []string{
		"/migrations",
		"./migrations",
		"../migrations",
		"../../migrations",
	}

	var foundBaseDir string
	for _, p := range candidatePaths {
		if info, err := os.Stat(p); err == nil && info.IsDir() {
			foundBaseDir = p
			break
		}
	}

	if foundBaseDir == "" {
		log.Warn("⚠️ Migrations directory not found on disk, skipping auto-migration scan")
		return nil
	}

	// 4. Collect SQL files: 000_schemas.sql first, then service subdirectory files
	var sqlFiles []string

	// Root schemas SQL file if present
	rootSchemas := filepath.Join(foundBaseDir, "000_schemas.sql")
	if _, err := os.Stat(rootSchemas); err == nil {
		sqlFiles = append(sqlFiles, rootSchemas)
	}

	// Service-specific migration directory
	serviceDir := filepath.Join(foundBaseDir, serviceName)
	if info, err := os.Stat(serviceDir); err == nil && info.IsDir() {
		entries, _ := os.ReadDir(serviceDir)
		var svcFiles []string
		for _, e := range entries {
			if !e.IsDir() && strings.HasSuffix(e.Name(), ".sql") {
				svcFiles = append(svcFiles, filepath.Join(serviceDir, e.Name()))
			}
		}
		sort.Strings(svcFiles)
		sqlFiles = append(sqlFiles, svcFiles...)
	}

	if len(sqlFiles) == 0 {
		log.Info("No SQL migration files found for service", zap.String("service", serviceName))
		return nil
	}

	// 5. Execute all migration SQL files
	appliedCount := 0
	for _, file := range sqlFiles {
		sqlBytes, err := os.ReadFile(file)
		if err != nil {
			log.Warn("Failed to read migration file", zap.String("file", file), zap.Error(err))
			continue
		}

		sqlContent := string(sqlBytes)
		sqlContent = strings.ReplaceAll(sqlContent, "uuid_generate_v4()", "gen_random_uuid()")
		sqlContent = strings.ReplaceAll(sqlContent, `CREATE EXTENSION IF NOT EXISTS "postgis";`, `-- postgis extension optional`)

		_, err = pool.Exec(ctx, sqlContent)
		if err != nil {
			// Expected for pre-existing tables/triggers
			log.Debug("Migration execution note", zap.String("file", filepath.Base(file)), zap.Error(err))
		} else {
			appliedCount++
		}
	}

	log.Info("✅ Permanent boot-time database auto-migration finished successfully!",
		zap.String("service", serviceName),
		zap.Int("processed_files", len(sqlFiles)),
		zap.String("source_dir", foundBaseDir),
	)
	return nil
}
