-- ============================================================================
-- auth/009_user_account_status.sql — Moderation state for the admin console
-- Adds a coarse account status to core.users so admins can suspend/ban users.
-- Idempotent: boot-time AutoMigrate re-runs every file, so guard with IF NOT
-- EXISTS. Values: 'active' (default), 'suspended', 'banned'.
-- ============================================================================
SET search_path TO auth, core, public;

ALTER TABLE core.users
    ADD COLUMN IF NOT EXISTS account_status VARCHAR(20) NOT NULL DEFAULT 'active';

CREATE INDEX IF NOT EXISTS idx_core_users_account_status
    ON core.users(account_status);
