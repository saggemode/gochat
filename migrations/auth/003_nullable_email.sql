-- ============================================================================
-- auth/003_nullable_email.sql — Allow NULL emails for phone-only registration
-- ============================================================================
SET search_path TO core, auth, public;

-- Allow email to be NULL so users can register with phone number only
ALTER TABLE core.users ALTER COLUMN email DROP NOT NULL;
