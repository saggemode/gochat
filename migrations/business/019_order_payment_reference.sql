-- Paystack (and future gateway) payment reference for an order, used to match
-- the init call to the webhook/verify confirmation.
ALTER TABLE business.orders
    ADD COLUMN IF NOT EXISTS payment_reference TEXT;

CREATE INDEX IF NOT EXISTS idx_orders_payment_reference
    ON business.orders(payment_reference)
    WHERE payment_reference IS NOT NULL;
