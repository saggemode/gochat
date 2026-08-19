-- ============================================================================
-- 003_polls_folders_labels_analytics_notifications
-- Adds: polls, chat_folders, chat_labels, chat_analytics, notification_profiles
-- All in chat.* schema (already partly defined in 001_chat.sql)
-- ============================================================================
SET search_path TO chat, core, public;

-- ── Polls in chat conversations ───────────────────────────────────────────────
DO $$ BEGIN
    CREATE TABLE IF NOT EXISTS chat.polls (
        id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        conversation_id UUID NOT NULL REFERENCES chat.conversations(id) ON DELETE CASCADE,
        created_by      UUID NOT NULL REFERENCES core.users(id),
        question        TEXT NOT NULL,
        is_anonymous    BOOLEAN NOT NULL DEFAULT FALSE,
        is_multiple     BOOLEAN NOT NULL DEFAULT FALSE,
        is_closed       BOOLEAN NOT NULL DEFAULT FALSE,
        expires_at      TIMESTAMPTZ,
        created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );
EXCEPTION WHEN duplicate_table THEN NULL;
END $$;

CREATE INDEX IF NOT EXISTS idx_chat_polls_conv
    ON chat.polls(conversation_id, created_at DESC);

DO $$ BEGIN
    CREATE TABLE IF NOT EXISTS chat.poll_options (
        id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        poll_id    UUID NOT NULL REFERENCES chat.polls(id) ON DELETE CASCADE,
        text       VARCHAR(500) NOT NULL,
        sort_order INT NOT NULL DEFAULT 0
    );
EXCEPTION WHEN duplicate_table THEN NULL;
END $$;

CREATE INDEX IF NOT EXISTS idx_chat_poll_options_poll ON chat.poll_options(poll_id);

DO $$ BEGIN
    CREATE TABLE IF NOT EXISTS chat.poll_votes (
        id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        poll_id   UUID NOT NULL REFERENCES chat.polls(id) ON DELETE CASCADE,
        option_id UUID NOT NULL REFERENCES chat.poll_options(id) ON DELETE CASCADE,
        user_id   UUID NOT NULL REFERENCES core.users(id) ON DELETE CASCADE,
        voted_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
        UNIQUE(poll_id, option_id, user_id)
    );
EXCEPTION WHEN duplicate_table THEN NULL;
END $$;

CREATE INDEX IF NOT EXISTS idx_chat_poll_votes_poll   ON chat.poll_votes(poll_id);
CREATE INDEX IF NOT EXISTS idx_chat_poll_votes_option ON chat.poll_votes(option_id);
CREATE INDEX IF NOT EXISTS idx_chat_poll_votes_user   ON chat.poll_votes(user_id);

-- ── Chat Folders ─────────────────────────────────────────────────────────────
-- Schema already partly exists in 001_chat.sql; ensure columns/types are correct.
DO $$ BEGIN
    ALTER TABLE chat.chat_folder_items
        ADD COLUMN IF NOT EXISTS added_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
EXCEPTION WHEN duplicate_column THEN NULL; END $$;

-- ── Chat Labels (already in 001_chat.sql) ───────────────────────────────────
-- table: chat_labels(user_id, message_id, label, color, created_at)

-- ── Chat Analytics ──────────────────────────────────────────────────────────
-- table: chat_analytics(user_id, conversation_id, total_messages, avg_response_ms, last_active_at, busiest_hour, computed_at)

-- ── Notification Profiles (already in 001_chat.sql) ────────────────────────
-- table: notification_profiles(user_id, conversation_id, muted, mute_until, sound, vibration, priority, show_preview, notify_on_mention_only, created_at, updated_at)
