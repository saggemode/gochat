-- ============================================================================
-- 002_forwarding_and_mentions — Forwarding + @Mentions support
-- ============================================================================
SET search_path TO chat, core, public;

-- ── Forwarding support on messages ───────────────────────────────────────────
ALTER TABLE chat.messages
    ADD COLUMN IF NOT EXISTS forwarded_from_id     UUID,
    ADD COLUMN IF NOT EXISTS forwarded_from_conv   UUID,
    ADD COLUMN IF NOT EXISTS forwarded_from_sender UUID;

CREATE INDEX IF NOT EXISTS idx_messages_forwarded
    ON chat.messages(forwarded_from_id) WHERE forwarded_from_id IS NOT NULL;

-- ── Mentions ────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS chat.message_mentions (
    message_id  UUID        NOT NULL REFERENCES chat.messages(id) ON DELETE CASCADE,
    user_id     UUID        NOT NULL REFERENCES core.users(id) ON DELETE CASCADE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (message_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_message_mentions_user
    ON chat.message_mentions(user_id);
