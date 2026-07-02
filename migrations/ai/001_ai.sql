-- ============================================================================
-- ai/001_ai.sql — AI Service Schema
-- Tables: ai_summaries, smart_replies
-- ============================================================================
SET search_path TO ai, core, public;

-- ── AI Summaries ────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS ai.ai_summaries (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id UUID NOT NULL,
    user_id         UUID NOT NULL REFERENCES core.users(id) ON DELETE CASCADE,
    summary_text    TEXT NOT NULL,
    message_count   INT  NOT NULL DEFAULT 0,
    from_message_id UUID,
    to_message_id   UUID,
    language        VARCHAR(10) DEFAULT 'en',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_ai_summaries_conv
    ON ai.ai_summaries(conversation_id, user_id, created_at DESC);

-- ── Smart Reply Suggestions ─────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS ai.smart_replies (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id UUID NOT NULL,
    user_id         UUID NOT NULL REFERENCES core.users(id) ON DELETE CASCADE,
    suggestions     JSONB NOT NULL DEFAULT '[]',
    context_hash    VARCHAR(64),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMPTZ NOT NULL DEFAULT NOW() + INTERVAL '5 minutes'
);
CREATE INDEX IF NOT EXISTS idx_smart_replies_lookup
    ON ai.smart_replies(conversation_id, user_id, expires_at);
