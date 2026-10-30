-- Migration 025: Category-scoped coupons + auto-apply flag
-- category_id lets a coupon target a whole category (applies when the cart holds
-- any product in that category), complementing the existing store-wide and
-- single-product scopes. auto_apply marks a coupon the checkout applies on its
-- own (no code to type); the best auto-apply coupon a cart qualifies for wins.

ALTER TABLE business.coupons ADD COLUMN IF NOT EXISTS category_id UUID REFERENCES business.categories(id) ON DELETE SET NULL;
ALTER TABLE business.coupons ADD COLUMN IF NOT EXISTS auto_apply BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX IF NOT EXISTS idx_coupons_category ON business.coupons(category_id);
CREATE INDEX IF NOT EXISTS idx_coupons_auto_apply ON business.coupons(business_id, auto_apply) WHERE auto_apply = TRUE;
