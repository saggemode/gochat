-- ============================================================================
-- media/002_media_data.sql — Add data blob and file_name to media_files
-- Allows PostgreSQL to act as a resilient fallback storage provider.
-- ============================================================================
SET search_path TO media, core, public;

ALTER TABLE media.media_files ADD COLUMN IF NOT EXISTS data BYTEA;
ALTER TABLE media.media_files ADD COLUMN IF NOT EXISTS file_name TEXT DEFAULT '';
ALTER TABLE media.media_files ALTER COLUMN uploader_id DROP NOT NULL;
