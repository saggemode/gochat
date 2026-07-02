-- ============================================================================
-- story/001_story.sql — Story Service Schema
-- Tables: stories, story_views, story_polls, story_poll_votes,
--         story_qa, story_countdowns
-- ============================================================================
SET search_path TO story, core, public;

-- ── Stories ─────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS story.stories (
    id               UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id          UUID        NOT NULL REFERENCES core.users(id) ON DELETE CASCADE,
    media_url        TEXT        NOT NULL DEFAULT '',
    media_type       TEXT        NOT NULL DEFAULT 'text',
    content          TEXT        NOT NULL DEFAULT '',
    background_color TEXT        NOT NULL DEFAULT '',
    font_style       TEXT        NOT NULL DEFAULT '',
    expires_at       TIMESTAMPTZ NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_stories_user_id    ON story.stories(user_id);
CREATE INDEX IF NOT EXISTS idx_stories_expires_at ON story.stories(expires_at);

-- ── Story Views ─────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS story.story_views (
    story_id  UUID        NOT NULL REFERENCES story.stories(id) ON DELETE CASCADE,
    viewer_id UUID        NOT NULL REFERENCES core.users(id) ON DELETE CASCADE,
    viewed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (story_id, viewer_id)
);

-- ── Interactive Story Polls ─────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS story.story_polls (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    story_id   UUID NOT NULL REFERENCES story.stories(id) ON DELETE CASCADE,
    question   TEXT NOT NULL,
    option_a   VARCHAR(200) NOT NULL,
    option_b   VARCHAR(200) NOT NULL,
    votes_a    INT DEFAULT 0,
    votes_b    INT DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS story.story_poll_votes (
    poll_id   UUID NOT NULL REFERENCES story.story_polls(id) ON DELETE CASCADE,
    user_id   UUID NOT NULL REFERENCES core.users(id) ON DELETE CASCADE,
    choice    VARCHAR(1) NOT NULL,
    voted_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (poll_id, user_id)
);

-- ── Story Q&A ───────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS story.story_qa (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    story_id    UUID NOT NULL REFERENCES story.stories(id) ON DELETE CASCADE,
    question    TEXT NOT NULL,
    asked_by    UUID REFERENCES core.users(id),
    answer      TEXT,
    answered_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ── Story Countdowns ────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS story.story_countdowns (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    story_id   UUID NOT NULL REFERENCES story.stories(id) ON DELETE CASCADE,
    title      VARCHAR(300) NOT NULL,
    end_time   TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
