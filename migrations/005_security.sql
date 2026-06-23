-- GoChat Database Schema
-- Migration 005: Security & Privacy (E2EE, Blocks, 2FA)
-- Run via: goose postgres $POSTGRES_DSN up

-- ── Extend Users for 2FA and E2EE ─────────────────────────────────────────────
ALTER TABLE users ADD COLUMN IF NOT EXISTS two_factor_pin_hash TEXT;
ALTER TABLE users ADD COLUMN IF NOT EXISTS prekey_identity      TEXT;
ALTER TABLE users ADD COLUMN IF NOT EXISTS prekey_signed        TEXT;
ALTER TABLE users ADD COLUMN IF NOT EXISTS prekey_signature     TEXT;

-- ── User Blocks ───────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS user_blocks (
    blocker_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    blocked_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    blocked_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (blocker_id, blocked_id)
);

CREATE INDEX IF NOT EXISTS idx_user_blocks_blocker ON user_blocks(blocker_id);
CREATE INDEX IF NOT EXISTS idx_user_blocks_blocked ON user_blocks(blocked_id);

-- ── User Ephemeral E2EE Prekeys ───────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS user_prekeys (
    user_id    UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    key_id     INT NOT NULL,
    public_key TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, key_id)
);
