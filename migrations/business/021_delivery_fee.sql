-- Flat delivery fee a seller charges per order (added to the order total).
ALTER TABLE business.business_profiles
    ADD COLUMN IF NOT EXISTS delivery_fee NUMERIC NOT NULL DEFAULT 0;
