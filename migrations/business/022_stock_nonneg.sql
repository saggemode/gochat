-- Migration 022: Prevent negative stock (defense in depth)
-- Order creation already guards the decrement (SELECT ... FOR UPDATE + a
-- `quantity >= qty` guard), but a CHECK constraint ensures no code path can ever
-- drive stock below zero. Added NOT VALID so pre-existing rows (which may already
-- be negative from before these guards) don't block the migration; the constraint
-- is still enforced for every new write.
--
-- Wrapped in DO blocks because the boot-time auto-migrator re-runs every file and
-- Postgres has no "ADD CONSTRAINT IF NOT EXISTS".

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'products_quantity_nonneg') THEN
        ALTER TABLE business.products
            ADD CONSTRAINT products_quantity_nonneg CHECK (quantity >= 0) NOT VALID;
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'product_variants_stock_nonneg') THEN
        ALTER TABLE business.product_variants
            ADD CONSTRAINT product_variants_stock_nonneg CHECK (stock_quantity >= 0) NOT VALID;
    END IF;
END $$;
