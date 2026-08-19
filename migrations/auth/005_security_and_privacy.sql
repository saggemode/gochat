-- Migration: 005_security_and_privacy.sql
-- Description: Add tables and columns for sessions, recovery, audit logs, reports, and granular privacy controls.

CREATE TABLE IF NOT EXISTS users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email TEXT UNIQUE,
    password_hash TEXT DEFAULT '',
    display_name TEXT DEFAULT '',
    avatar_url TEXT DEFAULT '',
    status_text TEXT DEFAULT '',
    is_online BOOLEAN DEFAULT false,
    last_seen TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    phone TEXT,
    phone_verified BOOLEAN DEFAULT false,
    pin TEXT,
    country_code TEXT DEFAULT ''
);

ALTER TABLE users 
ADD COLUMN IF NOT EXISTS recovery_email TEXT DEFAULT '',
ADD COLUMN IF NOT EXISTS recovery_code_hash TEXT DEFAULT '',
ADD COLUMN IF NOT EXISTS recovery_expires_at TIMESTAMP WITH TIME ZONE,
ADD COLUMN IF NOT EXISTS profile_photo_privacy TEXT DEFAULT 'everyone',
ADD COLUMN IF NOT EXISTS status_privacy TEXT DEFAULT 'everyone',
ADD COLUMN IF NOT EXISTS read_receipts_enabled BOOLEAN DEFAULT true,
ADD COLUMN IF NOT EXISTS online_privacy TEXT DEFAULT 'everyone';

-- Active Sessions / Devices Table
CREATE TABLE IF NOT EXISTS user_sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_name TEXT NOT NULL,
    os TEXT NOT NULL,
    browser TEXT NOT NULL,
    ip_address TEXT NOT NULL,
    last_active_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_user_sessions_user_id ON user_sessions(user_id);

-- User Reports Table
CREATE TABLE IF NOT EXISTS user_reports (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    reporter_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    reported_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    reason TEXT NOT NULL,
    details TEXT DEFAULT '',
    evidence_msg_ids TEXT[] DEFAULT '{}',
    status TEXT DEFAULT 'PENDING',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_user_reports_reported_user_id ON user_reports(reported_user_id);

-- Security Audit Logs Table
CREATE TABLE IF NOT EXISTS security_audit_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    event_type TEXT NOT NULL,
    ip_address TEXT NOT NULL,
    user_agent TEXT NOT NULL,
    details TEXT DEFAULT '',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_security_audit_logs_user_id ON security_audit_logs(user_id);
