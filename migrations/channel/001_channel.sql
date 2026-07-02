-- ============================================================================
-- channel/001_channel.sql — Channel Service Schema
-- Tables: channels, channel_subscribers, channel_messages
-- ============================================================================
SET search_path TO channel, core, public;

CREATE TABLE IF NOT EXISTS channel.channels (
    id          UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    name        TEXT        NOT NULL,
    description TEXT        NOT NULL DEFAULT '',
    created_by  UUID        NOT NULL REFERENCES core.users(id) ON DELETE CASCADE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_channels_creator ON channel.channels(created_by);

CREATE TABLE IF NOT EXISTS channel.channel_subscribers (
    channel_id    UUID        NOT NULL REFERENCES channel.channels(id) ON DELETE CASCADE,
    user_id       UUID        NOT NULL REFERENCES core.users(id) ON DELETE CASCADE,
    subscribed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (channel_id, user_id)
);

CREATE TABLE IF NOT EXISTS channel.channel_messages (
    id         UUID            PRIMARY KEY DEFAULT uuid_generate_v4(),
    channel_id UUID            NOT NULL REFERENCES channel.channels(id) ON DELETE CASCADE,
    sender_id  UUID            NOT NULL REFERENCES core.users(id) ON DELETE CASCADE,
    content    TEXT            NOT NULL DEFAULT '',
    type       message_type    NOT NULL DEFAULT 'text',
    media_url  TEXT            NOT NULL DEFAULT '',
    media_mime TEXT            NOT NULL DEFAULT '',
    media_size BIGINT          NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_channel_messages_channel ON channel.channel_messages(channel_id);
