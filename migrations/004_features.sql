-- GoChat Database Schema
-- Migration 004: Features (Groups, Stories, Calls)
-- Run via: goose postgres $POSTGRES_DSN up

-- ── Group Metadata ───────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS group_metadata (
    conversation_id        UUID        PRIMARY KEY REFERENCES conversations(id) ON DELETE CASCADE,
    description            TEXT        NOT NULL DEFAULT '',
    announcements_only     BOOLEAN     NOT NULL DEFAULT FALSE,
    admins_only_edit_info  BOOLEAN     NOT NULL DEFAULT TRUE,
    invite_code            TEXT        UNIQUE,
    join_approval_required BOOLEAN     NOT NULL DEFAULT FALSE
);

-- ── Group Join Approvals ──────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS group_join_approvals (
    id              UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    conversation_id UUID        NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    user_id         UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    status          TEXT        NOT NULL DEFAULT 'pending', -- 'pending', 'approved', 'rejected'
    requested_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    resolved_by     UUID                 REFERENCES users(id),
    resolved_at     TIMESTAMPTZ,
    CONSTRAINT unique_pending_request UNIQUE (conversation_id, user_id, status)
);

CREATE INDEX IF NOT EXISTS idx_group_join_approvals_conversation ON group_join_approvals(conversation_id);

-- ── Stories ───────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS stories (
    id               UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id          UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    media_url        TEXT        NOT NULL DEFAULT '',
    media_type       TEXT        NOT NULL DEFAULT 'text', -- 'text', 'image', 'video'
    content          TEXT        NOT NULL DEFAULT '',
    background_color TEXT        NOT NULL DEFAULT '',
    font_style       TEXT        NOT NULL DEFAULT '',
    expires_at       TIMESTAMPTZ NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_stories_user_id ON stories(user_id);
CREATE INDEX IF NOT EXISTS idx_stories_expires_at ON stories(expires_at);

-- ── Story Views ───────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS story_views (
    story_id  UUID        NOT NULL REFERENCES stories(id) ON DELETE CASCADE,
    viewer_id UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    viewed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (story_id, viewer_id)
);

-- ── Call History ──────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS calls (
    id           UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    caller_id    UUID        NOT NULL REFERENCES users(id),
    receiver_id  UUID        NOT NULL REFERENCES users(id),
    type         TEXT        NOT NULL DEFAULT 'voice', -- 'voice', 'video'
    status       TEXT        NOT NULL DEFAULT 'dialing', -- 'dialing', 'active', 'rejected', 'missed', 'ended', 'busy'
    start_time   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    end_time     TIMESTAMPTZ,
    duration_sec INT         NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_calls_caller_id ON calls(caller_id);
CREATE INDEX IF NOT EXISTS idx_calls_receiver_id ON calls(receiver_id);
CREATE INDEX IF NOT EXISTS idx_calls_start_time ON calls(start_time DESC);
