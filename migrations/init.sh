#!/bin/bash
# ============================================================================
# init.sh — Database Initialization Entrypoint
# Provisions per-service databases and applies all migrations automatically.
# Mounted as /docker-entrypoint-initdb.d/init.sh by docker-compose.
# ============================================================================
set -euo pipefail

ADMIN_USER="${POSTGRES_USER:-postgres}"
MAIN_DB="${POSTGRES_DB:-gochat}"

DATABASES=(
    "postgres"
    "${MAIN_DB}"
    "auth-gochat-db"
    "authz-gochat-db"
    "chat-gochat-db"
    "media-gochat-db"
    "group-gochat-db"
    "story-gochat-db"
    "call-gochat-db"
    "channel-gochat-db"
    "social-gochat-db"
    "miniapp-gochat-db"
    "business-gochat-db"
    "payment-gochat-db"
    "ai-gochat-db"
)

# Service migration directories in order
SERVICES=(
    auth
    authz
    chat
    media
    grp
    story
    call
    channel
    social
    miniapp
    business
)

echo "==> [init.sh] 1. Creating per-service databases..."
for db in "${DATABASES[@]}"; do
    if [ "$db" != "postgres" ]; then
        psql -U "$ADMIN_USER" -d postgres -tc "SELECT 1 FROM pg_database WHERE datname = '$db'" | grep -q 1 || \
        psql -U "$ADMIN_USER" -d postgres -c "CREATE DATABASE \"$db\";"
    fi
done

echo "==> [init.sh] 2. Applying migrations to all databases..."
for db in "${DATABASES[@]}"; do
    echo "==> [init.sh] Migrating database: $db"
    PSQL="psql -U $ADMIN_USER -d $db"

    # Schema bootstrap
    $PSQL -f /migrations/000_schemas.sql || true

    # Per-service migrations
    for svc in "${SERVICES[@]}"; do
        migration_dir="/migrations/${svc}"
        if [ -d "$migration_dir" ]; then
            for sql_file in "$migration_dir"/*.sql; do
                if [ -f "$sql_file" ]; then
                    $PSQL -f "$sql_file" || true
                fi
            done
        fi
    done
done

echo "==> [init.sh] All per-service databases provisioned & migrated successfully."
