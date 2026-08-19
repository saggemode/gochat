-- ============================================================================
-- business/004_marketplace_phase3.sql — Wishlists Schema
-- Tables: wishlists
-- ============================================================================
SET search_path TO business, core, public;

CREATE TABLE IF NOT EXISTS business.wishlists (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID NOT NULL REFERENCES core.users(id) ON DELETE CASCADE,
    product_id UUID NOT NULL REFERENCES business.products(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(user_id, product_id)
);

CREATE INDEX IF NOT EXISTS idx_wishlists_user ON business.wishlists(user_id);
