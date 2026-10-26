-- User-submitted reports (stories, products, messages, users) → admin review queue.
CREATE TABLE IF NOT EXISTS social.reports (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    reporter_id  UUID        NOT NULL,
    target_type  TEXT        NOT NULL,              -- user | story | product | message
    target_id    TEXT        NOT NULL,
    reason       TEXT        NOT NULL DEFAULT '',
    status       TEXT        NOT NULL DEFAULT 'pending', -- pending | reviewed | actioned | dismissed
    reviewer_id  UUID,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_reports_status      ON social.reports(status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_reports_target      ON social.reports(target_type, target_id);
-- A reporter can't spam the same target repeatedly while a report is still pending.
CREATE UNIQUE INDEX IF NOT EXISTS idx_reports_unique_pending
    ON social.reports(reporter_id, target_type, target_id)
    WHERE status = 'pending';
