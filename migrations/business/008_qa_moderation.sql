-- ============================================================================
-- business/008_qa_moderation.sql — Add moderation fields to product Q&A
-- Tables: product_questions
-- ============================================================================

SET search_path TO business, core, public;

ALTER TABLE business.product_questions
ADD COLUMN IF NOT EXISTS is_flagged BOOLEAN DEFAULT FALSE,
ADD COLUMN IF NOT EXISTS flag_reason TEXT,
ADD COLUMN IF NOT EXISTS status VARCHAR(20) DEFAULT 'pending', -- pending, approved, rejected, deleted
ADD COLUMN IF NOT EXISTS flag_count INTEGER DEFAULT 0,
ADD COLUMN IF NOT EXISTS moderated_at TIMESTAMP WITH TIME ZONE,
ADD COLUMN IF NOT EXISTS moderated_by VARCHAR(255);

-- Index for moderation queries
CREATE INDEX IF NOT EXISTS idx_product_questions_status ON business.product_questions(status);
CREATE INDEX IF NOT EXISTS idx_product_questions_flagged ON business.product_questions(is_flagged) WHERE is_flagged = TRUE;
