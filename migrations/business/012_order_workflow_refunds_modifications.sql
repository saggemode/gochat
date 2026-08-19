-- ============================================================================
-- business/012_order_workflow_refunds_modifications.sql — Order Workflow Enhancements
-- Adds: order status history, refunds, and order modifications
-- ============================================================================

SET search_path TO business, core, public;

-- ── Order Status History ─────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS business.order_status_history (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id     UUID NOT NULL REFERENCES business.orders(id) ON DELETE CASCADE,
    from_status  VARCHAR(30),
    to_status    VARCHAR(30) NOT NULL,
    changed_by   UUID REFERENCES core.users(id),
    change_reason TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_order_status_history_order 
ON business.order_status_history(order_id, created_at DESC);

-- ── Refunds ─────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS business.refunds (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id            UUID NOT NULL REFERENCES business.orders(id) ON DELETE CASCADE,
    refund_amount       DECIMAL(15,2) NOT NULL,
    refund_reason       TEXT NOT NULL,
    refund_type         VARCHAR(30) NOT NULL DEFAULT 'full', -- 'full', 'partial'
    status              VARCHAR(30) NOT NULL DEFAULT 'pending', -- 'pending', 'approved', 'rejected', 'processed', 'failed'
    requested_by        UUID NOT NULL REFERENCES core.users(id),
    processed_by        UUID REFERENCES core.users(id),
    processed_at        TIMESTAMPTZ,
    rejection_reason    TEXT,
    refund_method       VARCHAR(50), -- 'original', 'store_credit', 'bank_transfer'
    refund_reference     VARCHAR(100), -- Transaction ID from payment processor
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_refunds_order ON business.refunds(order_id);
CREATE INDEX IF NOT EXISTS idx_refunds_status ON business.refunds(status);
CREATE INDEX IF NOT EXISTS idx_refunds_requested_by ON business.refunds(requested_by);

-- ── Order Modifications ───────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS business.order_modifications (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id            UUID NOT NULL REFERENCES business.orders(id) ON DELETE CASCADE,
    modification_type    VARCHAR(50) NOT NULL, -- 'shipping_address', 'items', 'quantity', 'cancel_item'
    old_value           JSONB,
    new_value           JSONB,
    reason              TEXT,
    requested_by        UUID NOT NULL REFERENCES core.users(id),
    approved_by         UUID REFERENCES core.users(id),
    status              VARCHAR(30) NOT NULL DEFAULT 'pending', -- 'pending', 'approved', 'rejected'
    rejection_reason    TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_order_modifications_order 
ON business.order_modifications(order_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_order_modifications_status 
ON business.order_modifications(status);

-- ── Refund Items (for partial refunds) ──────────────────────────────────────

CREATE TABLE IF NOT EXISTS business.refund_items (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    refund_id       UUID NOT NULL REFERENCES business.refunds(id) ON DELETE CASCADE,
    order_item_id   UUID NOT NULL REFERENCES business.order_items(id) ON DELETE CASCADE,
    quantity        INT NOT NULL,
    refund_amount   DECIMAL(15,2) NOT NULL,
    reason          TEXT
);

CREATE INDEX IF NOT EXISTS idx_refund_items_refund 
ON business.refund_items(refund_id);

-- ── Triggers for updated_at ─────────────────────────────────────────────────

CREATE OR REPLACE FUNCTION business.update_refunds_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS refunds_updated_at ON business.refunds;
CREATE TRIGGER refunds_updated_at
    BEFORE UPDATE ON business.refunds
    FOR EACH ROW EXECUTE FUNCTION business.update_refunds_updated_at();

CREATE OR REPLACE FUNCTION business.update_order_modifications_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS order_modifications_updated_at ON business.order_modifications;
CREATE TRIGGER order_modifications_updated_at
    BEFORE UPDATE ON business.order_modifications
    FOR EACH ROW EXECUTE FUNCTION business.update_order_modifications_updated_at();

-- ── Comments for Documentation ─────────────────────────────────────────────

COMMENT ON TABLE business.order_status_history IS 'History of all order status changes';
COMMENT ON TABLE business.refunds IS 'Refund requests and processing';
COMMENT ON TABLE business.order_modifications IS 'Order modification requests before shipping';
COMMENT ON TABLE business.refund_items IS 'Individual items in partial refunds';

COMMENT ON COLUMN business.refunds.refund_type IS 'Type of refund: full or partial';
COMMENT ON COLUMN business.refunds.status IS 'Refund status: pending, approved, rejected, processed, failed';
COMMENT ON COLUMN business.refunds.refund_method IS 'How refund is processed: original, store_credit, bank_transfer';
COMMENT ON COLUMN business.order_modifications.modification_type IS 'Type of modification: shipping_address, items, quantity, cancel_item';
