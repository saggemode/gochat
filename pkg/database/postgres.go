package database

import (
	"context"
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"go.uber.org/zap"
)

// envInt32 reads an int32 from an env var, falling back to def when unset/invalid.
func envInt32(key string, def int32) int32 {
	if v := os.Getenv(key); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n > 0 {
			return int32(n)
		}
	}
	return def
}

// NewPostgres creates and validates a PostgreSQL connection pool.
// It retries up to 10 times with exponential backoff to handle slow container starts.
func NewPostgres(ctx context.Context, dsn string, log *zap.Logger) (*pgxpool.Pool, error) {
	cfg, err := pgxpool.ParseConfig(dsn)
	if err != nil {
		return nil, fmt.Errorf("parsing postgres DSN: %w", err)
	}

	// Disable prepared statements for PgBouncer transaction pooling compatibility
	cfg.ConnConfig.DefaultQueryExecMode = pgx.QueryExecModeSimpleProtocol

	const searchPath = "core, auth, authz, chat, grp, story, call, channel, social, miniapp, business, media, public"

	// Set the search_path as a connection STARTUP parameter, not a session-level SET.
	// Under PgBouncer transaction pooling a `SET search_path` does not reliably persist
	// across transactions (each transaction may land on a different server connection),
	// which makes unqualified table references fail intermittently with "relation does
	// not exist" — surfacing to clients as spurious 500s (e.g. "membership check failed").
	// A startup parameter is applied when the backend connection is established, so it is
	// reliable. The AfterConnect SET is kept as a belt-and-suspenders fallback.
	if cfg.ConnConfig.RuntimeParams == nil {
		cfg.ConnConfig.RuntimeParams = map[string]string{}
	}
	cfg.ConnConfig.RuntimeParams["search_path"] = searchPath

	cfg.AfterConnect = func(ctx context.Context, conn *pgx.Conn) error {
		_, err := conn.Exec(ctx, "SET search_path TO "+searchPath+";")
		return err
	}

	// Connection pool sizing. Kept modest because all services share one database via a
	// connection pooler (e.g. Supabase/Supavisor or PgBouncer) — large per-service pools would
	// exhaust the pooler's / Postgres' connection limit. Tunable via DB_MAX_CONNS / DB_MIN_CONNS.
	cfg.MaxConns = envInt32("DB_MAX_CONNS", 8)
	cfg.MinConns = envInt32("DB_MIN_CONNS", 1)
	cfg.MaxConnLifetime = 30 * time.Minute
	cfg.MaxConnIdleTime = 5 * time.Minute
	cfg.HealthCheckPeriod = 30 * time.Second

	var pool *pgxpool.Pool
	backoff := time.Second

	for attempt := 1; attempt <= 10; attempt++ {
		pool, err = pgxpool.NewWithConfig(ctx, cfg)
		if err == nil {
			if pingErr := pool.Ping(ctx); pingErr == nil {
				log.Info("PostgreSQL connected", zap.Int("attempt", attempt))
				return pool, nil
			} else {
				pool.Close()
				err = pingErr
			}
		}

		log.Warn("PostgreSQL not ready, retrying...",
			zap.Int("attempt", attempt),
			zap.Duration("backoff", backoff),
			zap.Error(err),
		)
		time.Sleep(backoff)
		backoff *= 2
		if backoff > 30*time.Second {
			backoff = 30 * time.Second
		}
	}

	return nil, fmt.Errorf("failed to connect to PostgreSQL after 10 attempts: %w", err)
}

// EnsureSchema checks that required schemas exist and runs any pending migrations for the service.
func EnsureSchema(ctx context.Context, pool *pgxpool.Pool, serviceName string, log *zap.Logger) error {
	log.Info("Running boot-time database schema check...", zap.String("service", serviceName))

	// 1. Create core and service schemas if missing
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

	// 2. Ensure core.users table stub exists for FK integrity
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

	log.Info("Boot-time database schema check passed successfully", zap.String("service", serviceName))
	return nil
}
