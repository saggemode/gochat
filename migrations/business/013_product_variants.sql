-- ============================================================================
-- business/013_product_variants.sql — Product Variations & Attributes
-- Support multi-attribute variant selection (Size, Color, Material)
-- with variant-specific stock levels and price overrides
-- ============================================================================

SET search_path TO business, core, public;

-- ── 1. Create Product Variants Table ───────────────────────────────────────

CREATE TABLE IF NOT EXISTS business.product_variants (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id      UUID NOT NULL REFERENCES business.products(id) ON DELETE CASCADE,
    sku             VARCHAR(100),
    title           VARCHAR(255) NOT NULL, -- e.g. "Large / Black / Cotton"
    attributes_json JSONB NOT NULL DEFAULT '{}', -- e.g. {"size": "L", "color": "Black", "material": "Cotton"}
    price_override  DECIMAL(15,2), -- NULL means use product base price
    stock_quantity  INT NOT NULL DEFAULT 0,
    image_url       TEXT,
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_product_variants_product_id ON business.product_variants(product_id);
CREATE INDEX IF NOT EXISTS idx_product_variants_sku ON business.product_variants(sku);

-- ── 2. Add Variant Columns to Cart & Order Items ─────────────────────────────

ALTER TABLE business.cart_items 
ADD COLUMN IF NOT EXISTS variant_id UUID REFERENCES business.product_variants(id) ON DELETE SET NULL,
ADD COLUMN IF NOT EXISTS variant_title VARCHAR(255);

ALTER TABLE business.order_items 
ADD COLUMN IF NOT EXISTS variant_id UUID REFERENCES business.product_variants(id) ON DELETE SET NULL,
ADD COLUMN IF NOT EXISTS variant_title VARCHAR(255);
