-- ============================================================================
-- grp/003_group_events.sql — Group events (scheduled events + RSVPs)
-- Idempotent (AutoMigrate re-runs every file each boot).
-- ============================================================================
SET search_path TO grp, core, public;

CREATE TABLE IF NOT EXISTS grp.group_events (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id UUID        NOT NULL,
    title           TEXT        NOT NULL,
    description     TEXT        NOT NULL DEFAULT '',
    location        TEXT        NOT NULL DEFAULT '',
    event_time      TIMESTAMPTZ NOT NULL,
    created_by      UUID        NOT NULL REFERENCES core.users(id) ON DELETE CASCADE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_group_events_conv ON grp.group_events(conversation_id, event_time);

CREATE TABLE IF NOT EXISTS grp.group_event_rsvps (
    event_id UUID NOT NULL REFERENCES grp.group_events(id) ON DELETE CASCADE,
    user_id  UUID NOT NULL REFERENCES core.users(id) ON DELETE CASCADE,
    response TEXT NOT NULL DEFAULT 'going', -- going | maybe | not_going
    PRIMARY KEY (event_id, user_id)
);
