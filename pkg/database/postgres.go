package database

import (
	"context"
	"fmt"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
	"go.uber.org/zap"
)

// NewPostgres creates and validates a PostgreSQL connection pool.
// It retries up to 10 times with exponential backoff to handle slow container starts.
func NewPostgres(ctx context.Context, dsn string, log *zap.Logger) (*pgxpool.Pool, error) {
	cfg, err := pgxpool.ParseConfig(dsn)
	if err != nil {
		return nil, fmt.Errorf("parsing postgres DSN: %w", err)
	}

	// Pool settings optimised for a microservice
	cfg.MaxConns = 20
	cfg.MinConns = 2
	cfg.MaxConnLifetime = 30 * time.Minute
	cfg.MaxConnIdleTime = 5 * time.Minute
	cfg.HealthCheckPeriod = 1 * time.Minute

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
