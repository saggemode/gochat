-- ============================================================================
-- 000_schemas.sql — Master Schema Bootstrap
-- Creates all service schemas, shared extensions, and the core.users table.
-- This file MUST run before any per-service migration.
-- ============================================================================

-- ── Extensions (installed in public, accessible by all schemas) ──────────────
SET search_path TO public;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS "pg_trgm";
CREATE EXTENSION IF NOT EXISTS "btree_gin";
CREATE EXTENSION IF NOT EXISTS "postgis";

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_proc p
        JOIN pg_namespace n ON p.pronamespace = n.oid
        WHERE p.proname = 'uuid_generate_v4'
          AND n.nspname = 'public'
    ) THEN
        CREATE OR REPLACE FUNCTION public.uuid_generate_v4()
        RETURNS uuid
        LANGUAGE sql
        AS $func$ SELECT gen_random_uuid(); $func$;
    END IF;
END;
$$;

RESET search_path;

-- ── Create Schemas ──────────────────────────────────────────────────────────
CREATE SCHEMA IF NOT EXISTS core;
CREATE SCHEMA IF NOT EXISTS auth;
CREATE SCHEMA IF NOT EXISTS authz;
CREATE SCHEMA IF NOT EXISTS chat;
CREATE SCHEMA IF NOT EXISTS media;
CREATE SCHEMA IF NOT EXISTS grp;
CREATE SCHEMA IF NOT EXISTS story;
CREATE SCHEMA IF NOT EXISTS call;
CREATE SCHEMA IF NOT EXISTS channel;
CREATE SCHEMA IF NOT EXISTS social;
CREATE SCHEMA IF NOT EXISTS miniapp;
CREATE SCHEMA IF NOT EXISTS business;

-- ── Shared Enums (in public, visible to all via search_path) ────────────────
DO $$ BEGIN
    CREATE TYPE conversation_type AS ENUM ('direct', 'group');
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

DO $$ BEGIN
    CREATE TYPE message_type AS ENUM ('text', 'image', 'video', 'audio', 'voice', 'file');
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

DO $$ BEGIN
    CREATE TYPE message_status AS ENUM ('pending', 'sent', 'delivered', 'read', 'failed', 'scheduled');
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

-- ── Core Users Table ────────────────────────────────────────────────────────
-- Owned by the auth service, but readable by all via search_path.
CREATE TABLE IF NOT EXISTS core.users (
    id              UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    email           TEXT        NOT NULL UNIQUE,
    password_hash   TEXT        NOT NULL,
    display_name    TEXT        NOT NULL,
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
    pin                 TEXT        DEFAULT '',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_core_users_phone_unique
    ON core.users(phone) WHERE phone IS NOT NULL AND phone != '';

-- Auto-update updated_at trigger for core.users
CREATE OR REPLACE FUNCTION core.update_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER users_updated_at
    BEFORE UPDATE ON core.users
    FOR EACH ROW EXECUTE FUNCTION core.update_updated_at();
