-- ============================================================================
-- story/002_story_reshare.sql — Add reshare support to story service
-- ============================================================================
SET search_path TO story, core, public;

ALTER TABLE story.stories ADD COLUMN IF NOT EXISTS allow_reshare BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE story.stories ADD COLUMN IF NOT EXISTS is_reshare BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE story.stories ADD COLUMN IF NOT EXISTS reshared_from_story_id UUID REFERENCES story.stories(id) ON DELETE SET NULL;
ALTER TABLE story.stories ADD COLUMN IF NOT EXISTS original_author_id UUID REFERENCES core.users(id) ON DELETE SET NULL;
ALTER TABLE story.stories ADD COLUMN IF NOT EXISTS original_author_name TEXT NOT NULL DEFAULT '';
