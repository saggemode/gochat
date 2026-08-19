-- ============================================================================
-- business/007_shipment_tracking.sql — Add shipment tracking fields to orders
-- Tables: orders
-- ============================================================================

SET search_path TO business, core, public;

ALTER TABLE business.orders
ADD COLUMN IF NOT EXISTS tracking_number VARCHAR(255),
ADD COLUMN IF NOT EXISTS tracking_carrier VARCHAR(100),
ADD COLUMN IF NOT EXISTS tracking_url TEXT,
ADD COLUMN IF NOT EXISTS estimated_delivery_date TIMESTAMP WITH TIME ZONE,
ADD COLUMN IF NOT EXISTS actual_delivery_date TIMESTAMP WITH TIME ZONE,
ADD COLUMN IF NOT EXISTS shipped_at TIMESTAMP WITH TIME ZONE,
ADD COLUMN IF NOT EXISTS delivery_notes TEXT;

-- Index for tracking lookups
CREATE INDEX IF NOT EXISTS idx_orders_tracking_number ON business.orders(tracking_number) WHERE tracking_number IS NOT NULL;
