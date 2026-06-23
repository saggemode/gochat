-- GoChat Database Schema
-- Migration 006: Communities & Broadcast Lists
-- Run via: goose postgres $POSTGRES_DSN up

-- ── Communities ───────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS communities (
    id          UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    name        TEXT        NOT NULL,
    description TEXT        NOT NULL DEFAULT '',
    created_by  UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_communities_creator ON communities(created_by);

-- ── Community Groups ──────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS community_groups (
    community_id    UUID NOT NULL REFERENCES communities(id) ON DELETE CASCADE,
    conversation_id UUID NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    PRIMARY KEY (community_id, conversation_id)
);

-- ── Broadcast Lists ───────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS broadcast_lists (
    id         UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    owner_id   UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name       TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_broadcast_lists_owner ON broadcast_lists(owner_id);

-- ── Broadcast Recipients ──────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS broadcast_recipients (
    broadcast_list_id UUID NOT NULL REFERENCES broadcast_lists(id) ON DELETE CASCADE,
    user_id           UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    PRIMARY KEY (broadcast_list_id, user_id)
);
