#!/bin/bash
# ============================================================================
# init.sh — Database Initialization Entrypoint
# Runs 000_schemas.sql first, then each per-service migration in order.
# Mounted as /docker-entrypoint-initdb.d/init.sh by docker-compose.
# ============================================================================
set -euo pipefail

PSQL="psql -v ON_ERROR_STOP=1 --username=${POSTGRES_USER:-postgres} --dbname=${POSTGRES_DB:-gochat}"

echo "==> [init.sh] Creating schemas and core tables..."
$PSQL -f /migrations/000_schemas.sql

# Service migration order (dependencies first)
SERVICES=(
    auth
    authz
    chat
    media
    grp
    story
    call
    channel
    ai
    payment
    social
    miniapp
    business
)

for svc in "${SERVICES[@]}"; do
    migration_dir="/migrations/${svc}"
    if [ -d "$migration_dir" ]; then
        for sql_file in "$migration_dir"/*.sql; do
            if [ -f "$sql_file" ]; then
                echo "==> [init.sh] Running migration: ${svc}/$(basename "$sql_file")"
                $PSQL -f "$sql_file"
            fi
        done
    else
        echo "==> [init.sh] WARNING: No migration directory for ${svc}"
    fi
done

echo "==> [init.sh] All migrations completed successfully."
