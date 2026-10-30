-- Migration 024: Coupon scheduling + first-order-only flag
-- starts_at lets a seller schedule a coupon to become valid in the future
-- (e.g. a weekend sale); first_order_only restricts it to buyers who have never
-- ordered from the store before. Usage analytics (redemptions, total discount)
-- are derived at read time from used_count and the orders table, so no new
-- counters are stored here.

ALTER TABLE business.coupons ADD COLUMN IF NOT EXISTS starts_at TIMESTAMPTZ;
ALTER TABLE business.coupons ADD COLUMN IF NOT EXISTS first_order_only BOOLEAN NOT NULL DEFAULT FALSE;
