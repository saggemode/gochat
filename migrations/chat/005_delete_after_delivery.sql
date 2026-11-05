-- ============================================================================
-- chat/005_delete_after_delivery.sql — WhatsApp-style delete-after-delivery
-- Per-recipient delivery tracking. Once every intended recipient of a message
-- has acknowledged delivery, the server purges its stored copy (handled by the
-- chat scheduler). Undelivered messages are retained until delivered or until a
-- max-retention TTL, then dropped.
-- ============================================================================
SET search_path TO chat, core, public;

-- One row per (message, recipient) once that recipient's device has received it.
CREATE TABLE IF NOT EXISTS chat.message_deliveries (
    message_id   UUID        NOT NULL REFERENCES chat.messages(id) ON DELETE CASCADE,
    user_id      UUID        NOT NULL REFERENCES core.users(id)    ON DELETE CASCADE,
    delivered_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (message_id, user_id)
);

-- Lookups when deciding whether a message is fully delivered.
CREATE INDEX IF NOT EXISTS idx_msg_deliveries_msg  ON chat.message_deliveries(message_id);
CREATE INDEX IF NOT EXISTS idx_msg_deliveries_user ON chat.message_deliveries(user_id);

-- Speeds up the stale-purge scan (messages older than the retention window).
CREATE INDEX IF NOT EXISTS idx_messages_created_live
    ON chat.messages(created_at)
    WHERE is_deleted = FALSE AND status <> 'scheduled';
