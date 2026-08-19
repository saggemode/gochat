-- ============================================================================
-- business/006_orders_fx_quote.sql — Persist locked checkout FX quotes
-- Tables: orders
-- ============================================================================
SET search_path TO business, core, public;

ALTER TABLE business.orders
ADD COLUMN IF NOT EXISTS fx_quote JSONB;
