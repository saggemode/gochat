-- Migration 023: Payment webhook idempotency / audit ledger
-- The order paid-transition is already idempotent (ConfirmPaymentByReference only
-- updates orders WHERE payment_status <> 'paid'), so retries can't double-credit.
-- This ledger records each payment reference the first time it's confirmed and
-- counts how many times Paystack re-delivered it, so duplicate deliveries are
-- observable/auditable and any future payment side-effects can gate on first-seen.

CREATE TABLE IF NOT EXISTS business.payment_events (
    reference        VARCHAR(100) PRIMARY KEY,
    orders_confirmed INT         NOT NULL DEFAULT 0,
    seen_count       INT         NOT NULL DEFAULT 1,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_seen_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
