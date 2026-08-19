-- Migration: 006_infrastructure_optimization.sql
-- Description: Composite indexes for high-throughput messaging, search, sessions, and analytics.

-- Users indexes
CREATE INDEX IF NOT EXISTS idx_users_phone_clean ON users (REGEXP_REPLACE(phone, '[^0-9]', '', 'g'));
CREATE INDEX IF NOT EXISTS idx_users_pin ON users (UPPER(pin));
CREATE INDEX IF NOT EXISTS idx_users_email_lower ON users (LOWER(email));
CREATE INDEX IF NOT EXISTS idx_users_display_name_lower ON users (LOWER(display_name));

-- User sessions indexes
CREATE INDEX IF NOT EXISTS idx_user_sessions_active ON user_sessions (user_id, last_active_at DESC);

-- Security audit logs indexes
CREATE INDEX IF NOT EXISTS idx_audit_logs_user_created ON security_audit_logs (user_id, created_at DESC);

-- User reports indexes
CREATE INDEX IF NOT EXISTS idx_user_reports_status ON user_reports (status, created_at DESC);
