-- GoChat Database Schema
-- Migration 001: Initial tables
-- Run via: goose postgres $POSTGRES_DSN up

-- ── Extensions ────────────────────────────────────────────────────────────────
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pg_trgm";   -- for full-text search
CREATE EXTENSION IF NOT EXISTS "btree_gin"; -- for GIN indexes on composite fields

-- ── Users ─────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS users (
    id              UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    email           TEXT        NOT NULL UNIQUE,
    password_hash   TEXT        NOT NULL,
    display_name    TEXT        NOT NULL,
    avatar_url      TEXT        NOT NULL DEFAULT '',
    status_text     TEXT        NOT NULL DEFAULT '',
    is_online       BOOLEAN     NOT NULL DEFAULT FALSE,
    last_seen       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ── Refresh Tokens ────────────────────────────────────────────────────────────
-- We store token JTI (JWT ID) to allow revoking specific sessions
CREATE TABLE IF NOT EXISTS refresh_tokens (
    id          UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id     UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    jti         TEXT        NOT NULL UNIQUE,  -- JWT ID claim
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked     BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ── Conversations ─────────────────────────────────────────────────────────────
CREATE TYPE conversation_type AS ENUM ('direct', 'group');

CREATE TABLE IF NOT EXISTS conversations (
    id              UUID                PRIMARY KEY DEFAULT uuid_generate_v4(),
    type            conversation_type   NOT NULL DEFAULT 'direct',
    name            TEXT                NOT NULL DEFAULT '',  -- group name
    avatar_url      TEXT                NOT NULL DEFAULT '',
    created_by      UUID                NOT NULL REFERENCES users(id),
    created_at      TIMESTAMPTZ         NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ         NOT NULL DEFAULT NOW()
);

-- ── Conversation Members ──────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS conversation_members (
    conversation_id UUID        NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    user_id         UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role            TEXT        NOT NULL DEFAULT 'member', -- 'owner', 'admin', 'member'
    joined_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_read_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),    -- for unread count calculation
    PRIMARY KEY (conversation_id, user_id)
);

-- ── Messages ──────────────────────────────────────────────────────────────────
CREATE TYPE message_type   AS ENUM ('text', 'image', 'video', 'audio', 'voice', 'file');
CREATE TYPE message_status AS ENUM ('pending', 'sent', 'delivered', 'read', 'failed', 'scheduled');

CREATE TABLE IF NOT EXISTS messages (
    id               UUID            PRIMARY KEY DEFAULT uuid_generate_v4(),
    conversation_id  UUID            NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    sender_id        UUID            NOT NULL REFERENCES users(id),
    content          TEXT            NOT NULL DEFAULT '',
    type             message_type    NOT NULL DEFAULT 'text',
    status           message_status  NOT NULL DEFAULT 'sent',

    -- Media fields
    media_url        TEXT            NOT NULL DEFAULT '',
    media_mime       TEXT            NOT NULL DEFAULT '',
    media_size       BIGINT          NOT NULL DEFAULT 0,

    -- Threading
    parent_id        UUID            REFERENCES messages(id) ON DELETE SET NULL,
    thread_count     INT             NOT NULL DEFAULT 0,

    -- GoChat exclusive features
    send_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),    -- scheduled delivery time
    expires_at       TIMESTAMPTZ,                               -- self-destruct timestamp (NULL = never)
    is_pinned        BOOLEAN         NOT NULL DEFAULT FALSE,
    is_edited        BOOLEAN         NOT NULL DEFAULT FALSE,
    is_deleted       BOOLEAN         NOT NULL DEFAULT FALSE,

    -- Full-text search vector (auto-updated by trigger)
    search_vector    TSVECTOR,

    created_at       TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

-- ── Message Edit History ──────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS message_edits (
    id          UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    message_id  UUID        NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    content     TEXT        NOT NULL,
    edited_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ── Message Reactions ─────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS message_reactions (
    message_id  UUID        NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    user_id     UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    emoji       TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (message_id, user_id, emoji)
);

-- ── Message Reads ─────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS message_reads (
    message_id  UUID        NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    user_id     UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    read_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (message_id, user_id)
);

-- ── Pinned Messages (per conversation) ───────────────────────────────────────
CREATE TABLE IF NOT EXISTS pinned_messages (
    conversation_id UUID        NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    message_id      UUID        NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    pinned_by       UUID        NOT NULL REFERENCES users(id),
    pinned_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (conversation_id, message_id)
);

-- ── Media Files ───────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS media_files (
    id           UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    object_key   TEXT        NOT NULL UNIQUE,
    url          TEXT        NOT NULL,
    mime_type    TEXT        NOT NULL,
    size_bytes   BIGINT      NOT NULL DEFAULT 0,
    media_type   TEXT        NOT NULL DEFAULT 'file',
    width        INT         NOT NULL DEFAULT 0,
    height       INT         NOT NULL DEFAULT 0,
    duration_sec INT         NOT NULL DEFAULT 0,
    uploader_id  UUID        NOT NULL REFERENCES users(id),
    uploaded_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ── Triggers ──────────────────────────────────────────────────────────────────

-- Auto-update updated_at on users
CREATE OR REPLACE FUNCTION update_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER users_updated_at
    BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

CREATE TRIGGER conversations_updated_at
    BEFORE UPDATE ON conversations
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

CREATE TRIGGER messages_updated_at
    BEFORE UPDATE ON messages
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

-- Auto-update full-text search vector on messages
CREATE OR REPLACE FUNCTION messages_search_vector_update()
RETURNS TRIGGER AS $$
BEGIN
    NEW.search_vector = to_tsvector('english', COALESCE(NEW.content, ''));
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER messages_search_vector
    BEFORE INSERT OR UPDATE OF content ON messages
    FOR EACH ROW EXECUTE FUNCTION messages_search_vector_update();

-- Auto-increment thread_count on parent message when a reply is inserted
CREATE OR REPLACE FUNCTION increment_thread_count()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.parent_id IS NOT NULL THEN
        UPDATE messages SET thread_count = thread_count + 1
        WHERE id = NEW.parent_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER messages_thread_count
    AFTER INSERT ON messages
    FOR EACH ROW EXECUTE FUNCTION increment_thread_count();

-- Update conversation updated_at when a new message arrives
CREATE OR REPLACE FUNCTION update_conversation_on_message()
RETURNS TRIGGER AS $$
BEGIN
    UPDATE conversations SET updated_at = NOW()
    WHERE id = NEW.conversation_id;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER messages_update_conversation
    AFTER INSERT ON messages
    FOR EACH ROW EXECUTE FUNCTION update_conversation_on_message();
