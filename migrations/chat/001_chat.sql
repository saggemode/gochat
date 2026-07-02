-- ============================================================================
-- chat/001_chat.sql — Chat Service Schema
-- Tables: conversations, conversation_members, messages, message_edits,
--         message_reactions, message_reads, pinned_messages,
--         chat_folders, chat_folder_items, chat_labels, chat_analytics,
--         notification_profiles
-- ============================================================================
SET search_path TO chat, core, public;

-- ── Conversations ───────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS chat.conversations (
    id              UUID                PRIMARY KEY DEFAULT uuid_generate_v4(),
    type            conversation_type   NOT NULL DEFAULT 'direct',
    name            TEXT                NOT NULL DEFAULT '',
    avatar_url      TEXT                NOT NULL DEFAULT '',
    created_by      UUID                NOT NULL REFERENCES core.users(id),
    created_at      TIMESTAMPTZ         NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ         NOT NULL DEFAULT NOW()
);

-- ── Conversation Members ────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS chat.conversation_members (
    conversation_id UUID        NOT NULL REFERENCES chat.conversations(id) ON DELETE CASCADE,
    user_id         UUID        NOT NULL REFERENCES core.users(id) ON DELETE CASCADE,
    role            TEXT        NOT NULL DEFAULT 'member',
    joined_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_read_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (conversation_id, user_id)
);

-- ── Messages ────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS chat.messages (
    id               UUID            PRIMARY KEY DEFAULT uuid_generate_v4(),
    conversation_id  UUID            NOT NULL REFERENCES chat.conversations(id) ON DELETE CASCADE,
    sender_id        UUID            NOT NULL REFERENCES core.users(id),
    content          TEXT            NOT NULL DEFAULT '',
    type             message_type    NOT NULL DEFAULT 'text',
    status           message_status  NOT NULL DEFAULT 'sent',
    media_url        TEXT            NOT NULL DEFAULT '',
    media_mime       TEXT            NOT NULL DEFAULT '',
    media_size       BIGINT          NOT NULL DEFAULT 0,
    parent_id        UUID            REFERENCES chat.messages(id) ON DELETE SET NULL,
    thread_count     INT             NOT NULL DEFAULT 0,
    send_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    expires_at       TIMESTAMPTZ,
    is_pinned        BOOLEAN         NOT NULL DEFAULT FALSE,
    is_edited        BOOLEAN         NOT NULL DEFAULT FALSE,
    is_deleted       BOOLEAN         NOT NULL DEFAULT FALSE,
    search_vector    TSVECTOR,
    created_at       TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

-- ── Message Edit History ────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS chat.message_edits (
    id          UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    message_id  UUID        NOT NULL REFERENCES chat.messages(id) ON DELETE CASCADE,
    content     TEXT        NOT NULL,
    edited_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ── Message Reactions ───────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS chat.message_reactions (
    message_id  UUID        NOT NULL REFERENCES chat.messages(id) ON DELETE CASCADE,
    user_id     UUID        NOT NULL REFERENCES core.users(id) ON DELETE CASCADE,
    emoji       TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (message_id, user_id, emoji)
);

-- ── Message Reads ───────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS chat.message_reads (
    message_id  UUID        NOT NULL REFERENCES chat.messages(id) ON DELETE CASCADE,
    user_id     UUID        NOT NULL REFERENCES core.users(id) ON DELETE CASCADE,
    read_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (message_id, user_id)
);

-- ── Pinned Messages ─────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS chat.pinned_messages (
    conversation_id UUID        NOT NULL REFERENCES chat.conversations(id) ON DELETE CASCADE,
    message_id      UUID        NOT NULL REFERENCES chat.messages(id) ON DELETE CASCADE,
    pinned_by       UUID        NOT NULL REFERENCES core.users(id),
    pinned_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (conversation_id, message_id)
);

-- ── Chat Folders ────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS chat.chat_folders (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID NOT NULL REFERENCES core.users(id) ON DELETE CASCADE,
    name       VARCHAR(100) NOT NULL,
    icon       VARCHAR(50) DEFAULT 'folder',
    color      VARCHAR(7)  DEFAULT '#6366f1',
    sort_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(user_id, name)
);

CREATE TABLE IF NOT EXISTS chat.chat_folder_items (
    folder_id       UUID NOT NULL REFERENCES chat.chat_folders(id) ON DELETE CASCADE,
    conversation_id UUID NOT NULL REFERENCES chat.conversations(id) ON DELETE CASCADE,
    added_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (folder_id, conversation_id)
);

-- ── Chat Labels / Tags ─────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS chat.chat_labels (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID NOT NULL REFERENCES core.users(id) ON DELETE CASCADE,
    message_id UUID NOT NULL REFERENCES chat.messages(id) ON DELETE CASCADE,
    label      VARCHAR(100) NOT NULL,
    color      VARCHAR(7) DEFAULT '#f59e0b',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(user_id, message_id, label)
);
CREATE INDEX IF NOT EXISTS idx_chat_labels_user ON chat.chat_labels(user_id, label);

-- ── Chat Analytics ──────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS chat.chat_analytics (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           UUID NOT NULL REFERENCES core.users(id) ON DELETE CASCADE,
    conversation_id   UUID NOT NULL REFERENCES chat.conversations(id) ON DELETE CASCADE,
    total_messages    INT NOT NULL DEFAULT 0,
    avg_response_ms   BIGINT DEFAULT 0,
    last_active_at    TIMESTAMPTZ,
    busiest_hour      SMALLINT DEFAULT 0,
    computed_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(user_id, conversation_id)
);

-- ── Notification Profiles ───────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS chat.notification_profiles (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES core.users(id) ON DELETE CASCADE,
    conversation_id UUID NOT NULL REFERENCES chat.conversations(id) ON DELETE CASCADE,
    muted           BOOLEAN DEFAULT FALSE,
    mute_until      TIMESTAMPTZ,
    sound           VARCHAR(100) DEFAULT 'default',
    vibration       BOOLEAN DEFAULT TRUE,
    priority        VARCHAR(20) DEFAULT 'normal',
    show_preview    BOOLEAN DEFAULT TRUE,
    notify_on_mention_only BOOLEAN DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(user_id, conversation_id)
);

-- ── Indexes ─────────────────────────────────────────────────────────────────
CREATE INDEX IF NOT EXISTS idx_messages_conversation ON chat.messages(conversation_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_messages_sender       ON chat.messages(sender_id);
CREATE INDEX IF NOT EXISTS idx_messages_parent       ON chat.messages(parent_id) WHERE parent_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_messages_search       ON chat.messages USING GIN(search_vector);
CREATE INDEX IF NOT EXISTS idx_messages_expires      ON chat.messages(expires_at) WHERE expires_at IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_conv_members_user     ON chat.conversation_members(user_id);
CREATE INDEX IF NOT EXISTS idx_conversations_updated ON chat.conversations(updated_at DESC);

-- ── Triggers ────────────────────────────────────────────────────────────────
CREATE OR REPLACE FUNCTION chat.update_updated_at()
RETURNS TRIGGER AS $$
BEGIN NEW.updated_at = NOW(); RETURN NEW; END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER conversations_updated_at
    BEFORE UPDATE ON chat.conversations
    FOR EACH ROW EXECUTE FUNCTION chat.update_updated_at();

CREATE TRIGGER messages_updated_at
    BEFORE UPDATE ON chat.messages
    FOR EACH ROW EXECUTE FUNCTION chat.update_updated_at();

-- Full-text search vector auto-update
CREATE OR REPLACE FUNCTION chat.messages_search_vector_update()
RETURNS TRIGGER AS $$
BEGIN
    NEW.search_vector = to_tsvector('english', COALESCE(NEW.content, ''));
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER messages_search_vector
    BEFORE INSERT OR UPDATE OF content ON chat.messages
    FOR EACH ROW EXECUTE FUNCTION chat.messages_search_vector_update();

-- Thread count auto-increment
CREATE OR REPLACE FUNCTION chat.increment_thread_count()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.parent_id IS NOT NULL THEN
        UPDATE chat.messages SET thread_count = thread_count + 1 WHERE id = NEW.parent_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER messages_thread_count
    AFTER INSERT ON chat.messages
    FOR EACH ROW EXECUTE FUNCTION chat.increment_thread_count();

-- Update conversation timestamp on new message
CREATE OR REPLACE FUNCTION chat.update_conversation_on_message()
RETURNS TRIGGER AS $$
BEGIN
    UPDATE chat.conversations SET updated_at = NOW() WHERE id = NEW.conversation_id;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER messages_update_conversation
    AFTER INSERT ON chat.messages
    FOR EACH ROW EXECUTE FUNCTION chat.update_conversation_on_message();
