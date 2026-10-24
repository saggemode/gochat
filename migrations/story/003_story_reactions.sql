-- Story reactions: one emoji reaction per (story, user). Re-reacting replaces the emoji.
CREATE TABLE IF NOT EXISTS story.story_reactions (
    story_id   UUID        NOT NULL REFERENCES story.stories(id) ON DELETE CASCADE,
    user_id    UUID        NOT NULL REFERENCES core.users(id) ON DELETE CASCADE,
    emoji      TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (story_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_story_reactions_story ON story.story_reactions(story_id);
