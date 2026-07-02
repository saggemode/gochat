-- ============================================================================
-- social/001_social.sql — Social Service Schema
-- Tables: user_followers, moments, moment_likes, moment_comments,
--         nearby_users, user_badges, audio_rooms, audio_room_participants,
--         notification_queue
-- ============================================================================
SET search_path TO social, core, public;

-- ── Followers / Public Profiles ─────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS social.user_followers (
    follower_id  UUID NOT NULL REFERENCES core.users(id) ON DELETE CASCADE,
    following_id UUID NOT NULL REFERENCES core.users(id) ON DELETE CASCADE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (follower_id, following_id)
);
CREATE INDEX IF NOT EXISTS idx_followers_following ON social.user_followers(following_id);

-- ── Moments (Social Feed) ───────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS social.moments (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID NOT NULL REFERENCES core.users(id) ON DELETE CASCADE,
    content       TEXT,
    media_url     TEXT,
    media_type    VARCHAR(20),
    visibility    VARCHAR(20) DEFAULT 'public',
    like_count    INT DEFAULT 0,
    comment_count INT DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_moments_user ON social.moments(user_id, created_at DESC);

CREATE TABLE IF NOT EXISTS social.moment_likes (
    moment_id  UUID NOT NULL REFERENCES social.moments(id) ON DELETE CASCADE,
    user_id    UUID NOT NULL REFERENCES core.users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (moment_id, user_id)
);

CREATE TABLE IF NOT EXISTS social.moment_comments (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    moment_id  UUID NOT NULL REFERENCES social.moments(id) ON DELETE CASCADE,
    user_id    UUID NOT NULL REFERENCES core.users(id) ON DELETE CASCADE,
    content    TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ── Nearby Users (opt-in) ───────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS social.nearby_users (
    user_id    UUID PRIMARY KEY REFERENCES core.users(id) ON DELETE CASCADE,
    latitude   DOUBLE PRECISION NOT NULL,
    longitude  DOUBLE PRECISION NOT NULL,
    location   GEOGRAPHY(Point, 4326) NOT NULL,
    is_visible BOOLEAN DEFAULT TRUE,
    radius_km  INT DEFAULT 5,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_nearby_users_location ON social.nearby_users USING gist(location);

-- ── Verification Badges ─────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS social.user_badges (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID NOT NULL REFERENCES core.users(id) ON DELETE CASCADE,
    badge_type VARCHAR(50) NOT NULL,
    granted_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ,
    UNIQUE(user_id, badge_type)
);

-- ── Audio Rooms ─────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS social.audio_rooms (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    title        VARCHAR(300) NOT NULL,
    created_by   UUID NOT NULL REFERENCES core.users(id),
    is_active    BOOLEAN DEFAULT TRUE,
    max_speakers INT DEFAULT 10,
    is_recording BOOLEAN DEFAULT FALSE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    ended_at     TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS social.audio_room_participants (
    room_id    UUID NOT NULL REFERENCES social.audio_rooms(id) ON DELETE CASCADE,
    user_id    UUID NOT NULL REFERENCES core.users(id) ON DELETE CASCADE,
    role       VARCHAR(20) DEFAULT 'listener',
    is_muted   BOOLEAN DEFAULT TRUE,
    joined_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    left_at    TIMESTAMPTZ,
    PRIMARY KEY (room_id, user_id)
);

-- ── AI-Prioritized Notification Queue ───────────────────────────────────────
CREATE TABLE IF NOT EXISTS social.notification_queue (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           UUID NOT NULL REFERENCES core.users(id) ON DELETE CASCADE,
    conversation_id   UUID NOT NULL,
    message_id        UUID,
    priority_score    FLOAT DEFAULT 0.5,
    notification_type VARCHAR(30) DEFAULT 'message',
    is_delivered      BOOLEAN DEFAULT FALSE,
    scheduled_for     TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_notif_queue_user
    ON social.notification_queue(user_id, is_delivered, priority_score DESC);
