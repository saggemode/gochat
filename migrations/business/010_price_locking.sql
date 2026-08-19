-- ============================================================================
-- business/010_price_locking.sql — Price Locking for Cart and Order Items
-- Adds price locking fields to prevent price changes during checkout
-- ============================================================================

SET search_path TO business, core, public;

-- ── Add Price Locking Fields to Cart Items ───────────────────────────────────

ALTER TABLE business.cart_items 
ADD COLUMN IF NOT EXISTS locked_price DECIMAL(15,2),
ADD COLUMN IF NOT EXISTS locked_discount_percent DECIMAL(5,2) DEFAULT 0,
ADD COLUMN IF NOT EXISTS price_locked_at TIMESTAMPTZ DEFAULT NOW();

-- ── Add Price Locking Fields to Order Items ───────────────────────────────────

ALTER TABLE business.order_items 
ADD COLUMN IF NOT EXISTS locked_price DECIMAL(15,2),
ADD COLUMN IF NOT EXISTS locked_discount_percent DECIMAL(5,2) DEFAULT 0,
ADD COLUMN IF NOT EXISTS price_changed BOOLEAN DEFAULT FALSE;

-- ── Add Index for Price Change Detection ──────────────────────────────────────

CREATE INDEX IF NOT EXISTS idx_order_items_price_changed 
ON business.order_items(price_changed) 
WHERE price_changed = TRUE;

-- ── Add Comment for Documentation ─────────────────────────────────────────────

COMMENT ON COLUMN business.cart_items.locked_price IS 'Product price locked when item was added to cart';
COMMENT ON COLUMN business.cart_items.locked_discount_percent IS 'Discount percentage locked when item was added to cart';
COMMENT ON COLUMN business.cart_items.price_locked_at IS 'Timestamp when price was locked';

COMMENT ON COLUMN business.order_items.locked_price IS 'Original locked price from cart at time of order';
COMMENT ON COLUMN business.order_items.locked_discount_percent IS 'Original locked discount from cart at time of order';
COMMENT ON COLUMN business.order_items.price_changed IS 'Whether the actual price differed from locked price at order time';
