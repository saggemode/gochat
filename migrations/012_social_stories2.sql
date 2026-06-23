-- 012_social_stories2.sql
-- Phase 8: Social Features + Stories 2.0 + Smart Notifications

-- ── Followers / Public Profiles ─────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS user_followers (
    follower_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    following_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (follower_id, following_id)
);
CREATE INDEX idx_followers_following ON user_followers(following_id);

-- ── Moments (Social Feed) ───────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS moments (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    content    TEXT,
    media_url  TEXT,
    media_type VARCHAR(20), -- 'image', 'video', 'text'
    visibility VARCHAR(20) DEFAULT 'public', -- 'public', 'followers', 'private'
    like_count INT DEFAULT 0,
    comment_count INT DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_moments_user ON moments(user_id, created_at DESC);

CREATE TABLE IF NOT EXISTS moment_likes (
    moment_id UUID NOT NULL REFERENCES moments(id) ON DELETE CASCADE,
    user_id   UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (moment_id, user_id)
);

CREATE TABLE IF NOT EXISTS moment_comments (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    moment_id  UUID NOT NULL REFERENCES moments(id) ON DELETE CASCADE,
    user_id    UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    content    TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ── Nearby Users (opt-in) ───────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS nearby_users (
    user_id    UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    latitude   DOUBLE PRECISION NOT NULL,
    longitude  DOUBLE PRECISION NOT NULL,
    is_visible BOOLEAN DEFAULT TRUE,
    radius_km  INT DEFAULT 5,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ── Verification Badges ─────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS user_badges (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    badge_type VARCHAR(50) NOT NULL, -- 'verified', 'business', 'creator', 'developer'
    granted_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ,
    UNIQUE(user_id, badge_type)
);

-- ── Audio Rooms ─────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS audio_rooms (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    title       VARCHAR(300) NOT NULL,
    created_by  UUID NOT NULL REFERENCES users(id),
    is_active   BOOLEAN DEFAULT TRUE,
    max_speakers INT DEFAULT 10,
    is_recording BOOLEAN DEFAULT FALSE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    ended_at    TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS audio_room_participants (
    room_id    UUID NOT NULL REFERENCES audio_rooms(id) ON DELETE CASCADE,
    user_id    UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role       VARCHAR(20) DEFAULT 'listener', -- 'host', 'speaker', 'listener'
    is_muted   BOOLEAN DEFAULT TRUE,
    joined_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    left_at    TIMESTAMPTZ,
    PRIMARY KEY (room_id, user_id)
);

-- ── Interactive Story Features ──────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS story_polls (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    story_id   UUID NOT NULL REFERENCES stories(id) ON DELETE CASCADE,
    question   TEXT NOT NULL,
    option_a   VARCHAR(200) NOT NULL,
    option_b   VARCHAR(200) NOT NULL,
    votes_a    INT DEFAULT 0,
    votes_b    INT DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS story_poll_votes (
    poll_id   UUID NOT NULL REFERENCES story_polls(id) ON DELETE CASCADE,
    user_id   UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    choice    VARCHAR(1) NOT NULL, -- 'a' or 'b'
    voted_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (poll_id, user_id)
);

CREATE TABLE IF NOT EXISTS story_qa (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    story_id    UUID NOT NULL REFERENCES stories(id) ON DELETE CASCADE,
    question    TEXT NOT NULL,
    asked_by    UUID REFERENCES users(id),
    answer      TEXT,
    answered_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS story_countdowns (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    story_id   UUID NOT NULL REFERENCES stories(id) ON DELETE CASCADE,
    title      VARCHAR(300) NOT NULL,
    end_time   TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ── AI-Prioritized Notification Queue ───────────────────────────────────────
CREATE TABLE IF NOT EXISTS notification_queue (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    conversation_id UUID NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    message_id      UUID,
    priority_score  FLOAT DEFAULT 0.5, -- 0.0 = low, 1.0 = highest
    notification_type VARCHAR(30) DEFAULT 'message', -- 'message', 'mention', 'reaction', 'call'
    is_delivered    BOOLEAN DEFAULT FALSE,
    scheduled_for   TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_notif_queue_user ON notification_queue(user_id, is_delivered, priority_score DESC);
