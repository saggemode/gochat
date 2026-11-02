-- ============================================================================
-- auth/010_must_change_password.sql — force password change on first login
-- Set true when an admin creates a staff account with a temporary password;
-- cleared when the user sets their own password. Idempotent.
-- ============================================================================
SET search_path TO auth, core, public;

ALTER TABLE core.users
    ADD COLUMN IF NOT EXISTS must_change_password BOOLEAN NOT NULL DEFAULT false;
