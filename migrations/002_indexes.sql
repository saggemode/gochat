-- Migration 002: Performance indexes

-- ── Users ─────────────────────────────────────────────────────────────────────
CREATE INDEX IF NOT EXISTS idx_users_email        ON users(email);
CREATE INDEX IF NOT EXISTS idx_users_display_name ON users USING gin(display_name gin_trgm_ops);

-- ── Refresh Tokens ────────────────────────────────────────────────────────────
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_user_id ON refresh_tokens(user_id);
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_jti     ON refresh_tokens(jti);
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_expires ON refresh_tokens(expires_at)
    WHERE revoked = FALSE;

-- ── Conversations ─────────────────────────────────────────────────────────────
CREATE INDEX IF NOT EXISTS idx_conversations_updated_at ON conversations(updated_at DESC);

-- ── Conversation Members ──────────────────────────────────────────────────────
CREATE INDEX IF NOT EXISTS idx_conv_members_user_id ON conversation_members(user_id);
CREATE INDEX IF NOT EXISTS idx_conv_members_conv_id ON conversation_members(conversation_id);

-- ── Messages ──────────────────────────────────────────────────────────────────
-- Main message list query: by conversation, ordered newest first
CREATE INDEX IF NOT EXISTS idx_messages_conv_created
    ON messages(conversation_id, created_at DESC)
    WHERE is_deleted = FALSE;

-- Scheduled messages poller
CREATE INDEX IF NOT EXISTS idx_messages_scheduled
    ON messages(send_at ASC)
    WHERE status = 'scheduled' AND is_deleted = FALSE;

-- Self-destruct cleaner
CREATE INDEX IF NOT EXISTS idx_messages_expires
    ON messages(expires_at ASC)
    WHERE expires_at IS NOT NULL AND is_deleted = FALSE;

-- Thread replies
CREATE INDEX IF NOT EXISTS idx_messages_parent
    ON messages(parent_id, created_at ASC)
    WHERE parent_id IS NOT NULL AND is_deleted = FALSE;

-- Full-text search
CREATE INDEX IF NOT EXISTS idx_messages_search
    ON messages USING gin(search_vector);

-- Pinned messages
CREATE INDEX IF NOT EXISTS idx_messages_pinned
    ON messages(conversation_id)
    WHERE is_pinned = TRUE AND is_deleted = FALSE;

-- ── Message Reactions ─────────────────────────────────────────────────────────
CREATE INDEX IF NOT EXISTS idx_reactions_message ON message_reactions(message_id);

-- ── Message Reads ─────────────────────────────────────────────────────────────
CREATE INDEX IF NOT EXISTS idx_reads_message ON message_reads(message_id);
CREATE INDEX IF NOT EXISTS idx_reads_user    ON message_reads(user_id);

-- ── Media Files ───────────────────────────────────────────────────────────────
CREATE INDEX IF NOT EXISTS idx_media_uploader ON media_files(uploader_id);
CREATE INDEX IF NOT EXISTS idx_media_key      ON media_files(object_key);
