-- Global per-user privacy toggle: may other people reshare my status/stories?
-- Defaults to TRUE (allowed), matching the previous per-status default.
ALTER TABLE users
ADD COLUMN IF NOT EXISTS allow_story_reshare BOOLEAN NOT NULL DEFAULT TRUE;
