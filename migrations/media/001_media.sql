-- ============================================================================
-- media/001_media.sql — Media Service Schema
-- Tables: media_files
-- ============================================================================
SET search_path TO media, core, public;

CREATE TABLE IF NOT EXISTS media.media_files (
    id           UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    object_key   TEXT        NOT NULL UNIQUE,
    url          TEXT        NOT NULL,
    mime_type    TEXT        NOT NULL,
    size_bytes   BIGINT      NOT NULL DEFAULT 0,
    media_type   TEXT        NOT NULL DEFAULT 'file',
    width        INT         NOT NULL DEFAULT 0,
    height       INT         NOT NULL DEFAULT 0,
    duration_sec INT         NOT NULL DEFAULT 0,
    uploader_id  UUID        NOT NULL REFERENCES core.users(id),
    uploaded_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_media_files_uploader ON media.media_files(uploader_id);
CREATE INDEX IF NOT EXISTS idx_media_files_type     ON media.media_files(media_type);
