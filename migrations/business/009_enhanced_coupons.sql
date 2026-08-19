-- Migration 009: Enhanced Configurable Coupons
ALTER TABLE business.coupons ADD COLUMN IF NOT EXISTS product_id UUID REFERENCES business.products(id) ON DELETE SET NULL;
ALTER TABLE business.coupons ADD COLUMN IF NOT EXISTS max_uses_per_user INT DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_coupons_product ON business.coupons(product_id);
