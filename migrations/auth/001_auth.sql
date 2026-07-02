-- ============================================================================
-- auth/001_auth.sql — Auth Service Schema
-- Tables: refresh_tokens, user_prekeys, user_push_tokens, user_blocks,
--         anonymous_ids, encrypted_backups, device_sessions, device_sync_cursors,
--         privacy_settings, stealth_config
-- ============================================================================
SET search_path TO auth, core, public;

-- ── Refresh Tokens ──────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS auth.refresh_tokens (
    id          UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id     UUID        NOT NULL REFERENCES core.users(id) ON DELETE CASCADE,
    jti         TEXT        NOT NULL UNIQUE,
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked     BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ── User Ephemeral E2EE Prekeys ─────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS auth.user_prekeys (
    user_id    UUID NOT NULL REFERENCES core.users(id) ON DELETE CASCADE,
    key_id     INT  NOT NULL,
    public_key TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, key_id)
);

-- ── Push Tokens ─────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS auth.user_push_tokens (
    user_id    UUID        NOT NULL REFERENCES core.users(id) ON DELETE CASCADE,
    push_token TEXT        NOT NULL,
    platform   TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, push_token)
);

-- ── User Blocks ─────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS auth.user_blocks (
    blocker_id UUID NOT NULL REFERENCES core.users(id) ON DELETE CASCADE,
    blocked_id UUID NOT NULL REFERENCES core.users(id) ON DELETE CASCADE,
    blocked_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (blocker_id, blocked_id)
);

CREATE INDEX IF NOT EXISTS idx_user_blocks_blocker ON auth.user_blocks(blocker_id);
CREATE INDEX IF NOT EXISTS idx_user_blocks_blocked ON auth.user_blocks(blocked_id);

-- ── Anonymous / Burner Identities ───────────────────────────────────────────
CREATE TABLE IF NOT EXISTS auth.anonymous_ids (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID NOT NULL REFERENCES core.users(id) ON DELETE CASCADE,
    alias        VARCHAR(100) NOT NULL UNIQUE,
    display_name VARCHAR(100) DEFAULT 'Anonymous',
    is_active    BOOLEAN DEFAULT TRUE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at   TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_anon_user ON auth.anonymous_ids(user_id);

-- ── Encrypted Cloud Backups ─────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS auth.encrypted_backups (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID NOT NULL REFERENCES core.users(id) ON DELETE CASCADE,
    backup_key    TEXT NOT NULL,
    size_bytes    BIGINT NOT NULL DEFAULT 0,
    message_count INT NOT NULL DEFAULT 0,
    media_count   INT NOT NULL DEFAULT 0,
    storage_url   TEXT,
    status        VARCHAR(20) NOT NULL DEFAULT 'pending',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at  TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_backups_user ON auth.encrypted_backups(user_id, created_at DESC);

-- ── Multi-Device Sessions ───────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS auth.device_sessions (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID NOT NULL REFERENCES core.users(id) ON DELETE CASCADE,
    device_name   VARCHAR(200) NOT NULL,
    device_type   VARCHAR(50) NOT NULL,
    platform      VARCHAR(50),
    last_active   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    push_token    TEXT,
    is_primary    BOOLEAN DEFAULT FALSE,
    is_active     BOOLEAN DEFAULT TRUE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_device_user ON auth.device_sessions(user_id, is_active);

CREATE TABLE IF NOT EXISTS auth.device_sync_cursors (
    device_id       UUID NOT NULL REFERENCES auth.device_sessions(id) ON DELETE CASCADE,
    conversation_id UUID NOT NULL,
    last_message_id UUID,
    synced_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (device_id, conversation_id)
);

-- ── Granular Privacy Settings ───────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS auth.privacy_settings (
    user_id               UUID PRIMARY KEY REFERENCES core.users(id) ON DELETE CASCADE,
    last_seen_visibility  VARCHAR(20) DEFAULT 'everyone',
    profile_photo_visibility VARCHAR(20) DEFAULT 'everyone',
    about_visibility      VARCHAR(20) DEFAULT 'everyone',
    status_visibility     VARCHAR(20) DEFAULT 'everyone',
    read_receipts         BOOLEAN DEFAULT TRUE,
    group_add_permission  VARCHAR(20) DEFAULT 'everyone',
    call_permission       VARCHAR(20) DEFAULT 'everyone',
    message_forwarding    BOOLEAN DEFAULT TRUE,
    screenshot_protection BOOLEAN DEFAULT FALSE,
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ── Stealth Mode Configuration ──────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS auth.stealth_config (
    user_id           UUID PRIMARY KEY REFERENCES core.users(id) ON DELETE CASCADE,
    hide_from_recents BOOLEAN DEFAULT FALSE,
    disguise_icon     VARCHAR(50),
    decoy_pin         VARCHAR(255),
    panic_wipe        BOOLEAN DEFAULT FALSE,
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
