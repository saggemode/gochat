-- 009_ai_schedule_organize.sql
-- Phase 5: AI Assistant, Chat Organization, Smart Notifications

-- ── AI Summaries ─────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS ai_summaries (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id UUID NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    summary_text    TEXT NOT NULL,
    message_count   INT  NOT NULL DEFAULT 0,
    from_message_id UUID,
    to_message_id   UUID,
    language        VARCHAR(10) DEFAULT 'en',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_ai_summaries_conv ON ai_summaries(conversation_id, user_id, created_at DESC);

-- ── Smart Reply Suggestions ──────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS smart_replies (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id UUID NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    suggestions     JSONB NOT NULL DEFAULT '[]', -- array of suggested reply strings
    context_hash    VARCHAR(64),                 -- hash of last N messages to avoid re-computing
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMPTZ NOT NULL DEFAULT NOW() + INTERVAL '5 minutes'
);
CREATE INDEX idx_smart_replies_lookup ON smart_replies(conversation_id, user_id, expires_at);

-- ── Chat Folders ─────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS chat_folders (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name       VARCHAR(100) NOT NULL,
    icon       VARCHAR(50) DEFAULT 'folder',
    color      VARCHAR(7)  DEFAULT '#6366f1',
    sort_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(user_id, name)
);

CREATE TABLE IF NOT EXISTS chat_folder_items (
    folder_id       UUID NOT NULL REFERENCES chat_folders(id) ON DELETE CASCADE,
    conversation_id UUID NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    added_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (folder_id, conversation_id)
);

-- ── Chat Labels / Tags ──────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS chat_labels (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    message_id UUID NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    label      VARCHAR(100) NOT NULL,
    color      VARCHAR(7) DEFAULT '#f59e0b',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(user_id, message_id, label)
);
CREATE INDEX idx_chat_labels_user ON chat_labels(user_id, label);

-- ── Chat Analytics ──────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS chat_analytics (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    conversation_id   UUID NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    total_messages    INT NOT NULL DEFAULT 0,
    avg_response_ms   BIGINT DEFAULT 0,
    last_active_at    TIMESTAMPTZ,
    busiest_hour      SMALLINT DEFAULT 0, -- 0-23
    computed_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(user_id, conversation_id)
);

-- ── Notification Profiles (Per-Contact/Group) ───────────────────────────────
CREATE TABLE IF NOT EXISTS notification_profiles (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    conversation_id UUID NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    muted           BOOLEAN DEFAULT FALSE,
    mute_until      TIMESTAMPTZ,
    sound           VARCHAR(100) DEFAULT 'default',
    vibration       BOOLEAN DEFAULT TRUE,
    priority        VARCHAR(20) DEFAULT 'normal', -- 'high', 'normal', 'low', 'silent'
    show_preview    BOOLEAN DEFAULT TRUE,
    notify_on_mention_only BOOLEAN DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(user_id, conversation_id)
);
