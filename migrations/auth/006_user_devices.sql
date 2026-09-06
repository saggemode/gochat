-- Migration: 006_user_devices.sql
-- Description: Ensure user_sessions / devices table and prekey registration ID are provisioned.

-- 1. Prekey registration ID on core.users
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = 'core' AND table_name = 'users') THEN
        ALTER TABLE core.users ADD COLUMN IF NOT EXISTS prekey_registration_id INT DEFAULT 0;
    ELSIF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'users') THEN
        ALTER TABLE users ADD COLUMN IF NOT EXISTS prekey_registration_id INT DEFAULT 0;
    END IF;
END $$;

-- 2. User Sessions / Linked Devices table
CREATE TABLE IF NOT EXISTS user_sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    device_name TEXT NOT NULL DEFAULT '',
    os TEXT NOT NULL DEFAULT '',
    browser TEXT NOT NULL DEFAULT '',
    ip_address TEXT NOT NULL DEFAULT '',
    platform TEXT NOT NULL DEFAULT 'android',
    client_version TEXT DEFAULT '',
    device_id TEXT DEFAULT '',
    last_active_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE user_sessions 
ADD COLUMN IF NOT EXISTS platform TEXT DEFAULT 'android',
ADD COLUMN IF NOT EXISTS client_version TEXT DEFAULT '',
ADD COLUMN IF NOT EXISTS device_id TEXT DEFAULT '';

CREATE INDEX IF NOT EXISTS idx_user_sessions_user_id ON user_sessions(user_id);
CREATE INDEX IF NOT EXISTS idx_user_sessions_device_id ON user_sessions(user_id, device_id);
