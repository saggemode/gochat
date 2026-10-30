-- Migration 026: Order disputes / resolution
-- A buyer can raise a dispute ("item not as described", "not delivered", etc.) on
-- a paid order; the seller sees it and resolves or rejects it with a note. Actual
-- money refunds continue to go through the existing Paystack refund flow — this
-- tracks the complaint and its resolution so the loop is visible to both sides.

CREATE TABLE IF NOT EXISTS business.order_disputes (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id     UUID        NOT NULL,
    business_id  UUID        NOT NULL,
    buyer_id     UUID        NOT NULL,
    reason       VARCHAR(100) NOT NULL,
    details      TEXT        NOT NULL DEFAULT '',
    status       VARCHAR(20) NOT NULL DEFAULT 'open', -- open, resolved, rejected
    resolution   TEXT        NOT NULL DEFAULT '',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    resolved_at  TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_disputes_business ON business.order_disputes(business_id, status);
CREATE INDEX IF NOT EXISTS idx_disputes_order ON business.order_disputes(order_id);
CREATE INDEX IF NOT EXISTS idx_disputes_buyer ON business.order_disputes(buyer_id);
