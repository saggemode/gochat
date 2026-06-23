-- GoChat Database Schema
-- Migration 008: Phone OTP & Push Notifications
-- Run via: goose postgres $POSTGRES_DSN up

-- ── Alter Users ──────────────────────────────────────
ALTER TABLE users ADD COLUMN IF NOT EXISTS phone TEXT;
ALTER TABLE users ADD COLUMN IF NOT EXISTS phone_verified BOOLEAN NOT NULL DEFAULT FALSE;

-- Unique partial index for non-empty phone numbers
CREATE UNIQUE INDEX IF NOT EXISTS idx_users_phone_unique ON users(phone) WHERE phone IS NOT NULL AND phone != '';

-- ── Push Tokens ───────────────────────────────────────
CREATE TABLE IF NOT EXISTS user_push_tokens (
    user_id    UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    push_token TEXT        NOT NULL,
    platform   TEXT        NOT NULL, -- 'android', 'ios', 'web'
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, push_token)
);
