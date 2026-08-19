-- ============================================================================
-- business/011_notification_preferences.sql — Notification Preferences Schema
-- Adds user notification preferences for business notifications
-- ============================================================================

SET search_path TO business, core, public;

-- ── Notification Preferences Table ───────────────────────────────────────────

CREATE TABLE IF NOT EXISTS business.notification_preferences (
    user_id                       UUID PRIMARY KEY REFERENCES core.users(id) ON DELETE CASCADE,
    enable_price_change_alerts     BOOLEAN DEFAULT TRUE,
    enable_order_updates          BOOLEAN DEFAULT TRUE,
    enable_promotional_alerts      BOOLEAN DEFAULT FALSE,
    preferred_channels             TEXT[] DEFAULT ARRAY['in_app']::TEXT[],
    price_change_threshold_percent DECIMAL(5,2) DEFAULT 5.0,
    quiet_hours_start             TIME,
    quiet_hours_end               TIME,
    created_at                    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ── Indexes ─────────────────────────────────────────────────────────────────

CREATE INDEX IF NOT EXISTS idx_notification_preferences_user 
ON business.notification_preferences(user_id);

-- ── Trigger for updated_at ───────────────────────────────────────────────────

CREATE OR REPLACE FUNCTION business.update_notification_preferences_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS notification_preferences_updated_at ON business.notification_preferences;
CREATE TRIGGER notification_preferences_updated_at
    BEFORE UPDATE ON business.notification_preferences
    FOR EACH ROW EXECUTE FUNCTION business.update_notification_preferences_updated_at();

-- ── Comments for Documentation ─────────────────────────────────────────────

COMMENT ON TABLE business.notification_preferences IS 'User preferences for business notifications';
COMMENT ON COLUMN business.notification_preferences.enable_price_change_alerts IS 'Enable alerts when cart prices change from locked prices';
COMMENT ON COLUMN business.notification_preferences.enable_order_updates IS 'Enable order status update notifications';
COMMENT ON COLUMN business.notification_preferences.enable_promotional_alerts IS 'Enable promotional/marketing notifications';
COMMENT ON COLUMN business.notification_preferences.preferred_channels IS 'Preferred notification channels: in_app, email, sms, push';
COMMENT ON COLUMN business.notification_preferences.price_change_threshold_percent IS 'Minimum price change percentage to trigger notification';
COMMENT ON COLUMN business.notification_preferences.quiet_hours_start IS 'Start time for quiet hours (no notifications)';
COMMENT ON COLUMN business.notification_preferences.quiet_hours_end IS 'End time for quiet hours (no notifications)';
