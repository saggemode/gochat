-- ============================================================================
-- social/003_nearby_postgis.sql — Nearby Users (opt-in), PostGIS-dependent
--
-- Isolated from 001 on purpose: the GEOGRAPHY column and GIST index require the
-- PostGIS extension. AutoMigrate runs each file as ONE batch, so if PostGIS is
-- unavailable this file fails ALONE — the core social tables (moments, likes,
-- comments) in 001 are unaffected and the Moments feed keeps working.
--
-- NOTE: AutoMigrate strips the double-quoted form `CREATE EXTENSION IF NOT
-- EXISTS "postgis";`, so this uses the UNQUOTED form, which it runs as-is. If the
-- database role may not create extensions, enable PostGIS once from the provider
-- dashboard (e.g. Supabase) instead; this file then just creates the table.
-- ============================================================================
SET search_path TO social, core, public;

CREATE EXTENSION IF NOT EXISTS postgis;

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
