-- 011_privacy_sync.sql
-- Phase 7: Advanced Privacy + Cross-Platform Sync

-- ── Anonymous / Burner Identities ───────────────────────────────────────────
CREATE TABLE IF NOT EXISTS anonymous_ids (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    alias        VARCHAR(100) NOT NULL UNIQUE,
    display_name VARCHAR(100) DEFAULT 'Anonymous',
    is_active    BOOLEAN DEFAULT TRUE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at   TIMESTAMPTZ
);
CREATE INDEX idx_anon_user ON anonymous_ids(user_id);

-- ── Encrypted Cloud Backups ─────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS encrypted_backups (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    backup_key    TEXT NOT NULL, -- encrypted backup encryption key
    size_bytes    BIGINT NOT NULL DEFAULT 0,
    message_count INT NOT NULL DEFAULT 0,
    media_count   INT NOT NULL DEFAULT 0,
    storage_url   TEXT,
    status        VARCHAR(20) NOT NULL DEFAULT 'pending', -- 'pending', 'uploading', 'completed', 'failed'
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at  TIMESTAMPTZ
);
CREATE INDEX idx_backups_user ON encrypted_backups(user_id, created_at DESC);

-- ── Multi-Device Sessions ───────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS device_sessions (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_name   VARCHAR(200) NOT NULL,
    device_type   VARCHAR(50) NOT NULL, -- 'phone', 'tablet', 'desktop', 'web'
    platform      VARCHAR(50), -- 'ios', 'android', 'windows', 'macos', 'linux', 'web'
    last_active   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    push_token    TEXT,
    is_primary    BOOLEAN DEFAULT FALSE,
    is_active     BOOLEAN DEFAULT TRUE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_device_user ON device_sessions(user_id, is_active);

CREATE TABLE IF NOT EXISTS device_sync_cursors (
    device_id       UUID NOT NULL REFERENCES device_sessions(id) ON DELETE CASCADE,
    conversation_id UUID NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    last_message_id UUID,
    synced_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (device_id, conversation_id)
);

-- ── Granular Privacy Settings ───────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS privacy_settings (
    user_id               UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    last_seen_visibility  VARCHAR(20) DEFAULT 'everyone', -- 'everyone', 'contacts', 'nobody'
    profile_photo_visibility VARCHAR(20) DEFAULT 'everyone',
    about_visibility      VARCHAR(20) DEFAULT 'everyone',
    status_visibility     VARCHAR(20) DEFAULT 'everyone',
    read_receipts         BOOLEAN DEFAULT TRUE,
    group_add_permission  VARCHAR(20) DEFAULT 'everyone', -- 'everyone', 'contacts', 'nobody'
    call_permission       VARCHAR(20) DEFAULT 'everyone',
    message_forwarding    BOOLEAN DEFAULT TRUE,
    screenshot_protection BOOLEAN DEFAULT FALSE,
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ── Stealth Mode Configuration ──────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS stealth_config (
    user_id           UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    hide_from_recents BOOLEAN DEFAULT FALSE,
    disguise_icon     VARCHAR(50), -- 'calculator', 'notes', 'weather', null = default
    decoy_pin         VARCHAR(255), -- bcrypt hash of decoy PIN
    panic_wipe        BOOLEAN DEFAULT FALSE, -- wipe data on decoy PIN entry
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
