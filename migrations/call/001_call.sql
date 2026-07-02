-- ============================================================================
-- call/001_call.sql — Call Service Schema
-- Tables: calls
-- ============================================================================
SET search_path TO call, core, public;

CREATE TABLE IF NOT EXISTS call.calls (
    id           UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    caller_id    UUID        NOT NULL REFERENCES core.users(id),
    receiver_id  UUID        NOT NULL REFERENCES core.users(id),
    type         TEXT        NOT NULL DEFAULT 'voice',
    status       TEXT        NOT NULL DEFAULT 'dialing',
    start_time   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    end_time     TIMESTAMPTZ,
    duration_sec INT         NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_calls_caller_id   ON call.calls(caller_id);
CREATE INDEX IF NOT EXISTS idx_calls_receiver_id  ON call.calls(receiver_id);
CREATE INDEX IF NOT EXISTS idx_calls_start_time   ON call.calls(start_time DESC);
