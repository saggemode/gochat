-- ============================================================================
-- chat/004_bot_support.sql — Bot Support for members
-- ============================================================================
SET search_path TO chat, core, public;

-- Add is_bot column to conversation_members
ALTER TABLE chat.conversation_members ADD COLUMN IF NOT EXISTS is_bot BOOLEAN NOT NULL DEFAULT FALSE;

-- Index for bot identification
CREATE INDEX IF NOT EXISTS idx_conv_members_bot ON chat.conversation_members(conversation_id, is_bot) WHERE is_bot = TRUE;
