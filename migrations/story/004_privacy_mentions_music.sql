-- ============================================================================
-- story/004_privacy_mentions_music.sql
-- A — Status privacy / audience (My contacts / except… / only share with…)
-- B — Status mentions (mention contacts; mentioned users always see + can reshare)
-- C — Music attached to a status
-- All statements idempotent (AutoMigrate re-runs every file each boot).
-- ============================================================================
SET search_path TO story, core, public;

-- ── A: per-status audience ──────────────────────────────────────────────────
-- audience_type: 'contacts' (all eligible contacts), 'except' (contacts minus the
-- list below), 'only' (ONLY the users in the list below, bypassing the contacts rule).
ALTER TABLE story.stories ADD COLUMN IF NOT EXISTS audience_type TEXT NOT NULL DEFAULT 'contacts';

CREATE TABLE IF NOT EXISTS story.story_audience (
    story_id   UUID NOT NULL REFERENCES story.stories(id) ON DELETE CASCADE,
    contact_id UUID NOT NULL REFERENCES core.users(id) ON DELETE CASCADE,
    PRIMARY KEY (story_id, contact_id)
);
CREATE INDEX IF NOT EXISTS idx_story_audience_story ON story.story_audience(story_id);

-- ── B: mentions ───────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS story.story_mentions (
    story_id          UUID NOT NULL REFERENCES story.stories(id) ON DELETE CASCADE,
    mentioned_user_id UUID NOT NULL REFERENCES core.users(id) ON DELETE CASCADE,
    PRIMARY KEY (story_id, mentioned_user_id)
);
CREATE INDEX IF NOT EXISTS idx_story_mentions_user ON story.story_mentions(mentioned_user_id);

-- ── C: music ────────────────────────────────────────────────────────────────
ALTER TABLE story.stories ADD COLUMN IF NOT EXISTS music_url      TEXT NOT NULL DEFAULT '';
ALTER TABLE story.stories ADD COLUMN IF NOT EXISTS music_title    TEXT NOT NULL DEFAULT '';
ALTER TABLE story.stories ADD COLUMN IF NOT EXISTS music_artist   TEXT NOT NULL DEFAULT '';
ALTER TABLE story.stories ADD COLUMN IF NOT EXISTS music_start_ms INT  NOT NULL DEFAULT 0;
