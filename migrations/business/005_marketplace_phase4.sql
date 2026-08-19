-- ============================================================================
-- business/005_marketplace_phase4.sql — Product Q&A Schema
-- Tables: product_questions
-- ============================================================================
SET search_path TO business, core, public;

CREATE TABLE IF NOT EXISTS business.product_questions (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id  UUID NOT NULL REFERENCES business.products(id) ON DELETE CASCADE,
    user_id     UUID NOT NULL REFERENCES core.users(id) ON DELETE CASCADE,
    question    TEXT NOT NULL,
    answer      TEXT,
    answered_by UUID REFERENCES core.users(id) ON DELETE SET NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_product_questions_prod ON business.product_questions(product_id);
