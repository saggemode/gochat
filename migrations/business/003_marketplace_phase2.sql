-- ============================================================================
-- business/003_marketplace_phase2.sql — Orders, Cart, Coupons Schema
-- Tables: carts, cart_items, orders, order_items, coupons
-- ============================================================================
SET search_path TO business, core, public;

-- ── Shopping Cart ────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS business.carts (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID NOT NULL UNIQUE REFERENCES core.users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS business.cart_items (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cart_id    UUID NOT NULL REFERENCES business.carts(id) ON DELETE CASCADE,
    product_id UUID NOT NULL REFERENCES business.products(id) ON DELETE CASCADE,
    quantity   INT NOT NULL DEFAULT 1 CHECK (quantity > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(cart_id, product_id)
);

CREATE INDEX IF NOT EXISTS idx_cart_items_cart ON business.cart_items(cart_id);

-- ── Coupons ─────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS business.coupons (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id    UUID NOT NULL REFERENCES business.business_profiles(user_id) ON DELETE CASCADE,
    code           VARCHAR(50) NOT NULL,
    discount_type  VARCHAR(20) NOT NULL DEFAULT 'percentage', -- 'percentage' or 'fixed'
    discount_value DECIMAL(10,2) NOT NULL CHECK (discount_value > 0),
    min_spend      DECIMAL(10,2) DEFAULT 0,
    max_uses       INT DEFAULT 0, -- 0 = unlimited
    used_count     INT DEFAULT 0,
    expires_at     TIMESTAMPTZ,
    is_active      BOOLEAN DEFAULT TRUE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(business_id, code)
);

CREATE INDEX IF NOT EXISTS idx_coupons_code ON business.coupons(code);

-- ── Orders & Order Items ────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS business.orders (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_number     VARCHAR(50) NOT NULL UNIQUE,
    buyer_id         UUID NOT NULL REFERENCES core.users(id) ON DELETE CASCADE,
    business_id      UUID NOT NULL REFERENCES business.business_profiles(user_id) ON DELETE CASCADE,
    total_amount     DECIMAL(15,2) NOT NULL,
    shipping_fee     DECIMAL(10,2) DEFAULT 0,
    discount_amount  DECIMAL(10,2) DEFAULT 0,
    grand_total      DECIMAL(15,2) NOT NULL,
    coupon_code      VARCHAR(50),
    status           VARCHAR(30) NOT NULL DEFAULT 'pending', -- pending, paid, processing, shipped, delivered, cancelled
    shipping_name    VARCHAR(200) NOT NULL,
    shipping_phone   VARCHAR(50) NOT NULL,
    shipping_address TEXT NOT NULL,
    shipping_city    VARCHAR(100),
    shipping_state   VARCHAR(100),
    shipping_country VARCHAR(100),
    payment_method   VARCHAR(50) DEFAULT 'card', -- card, transfer, wallet, cod
    payment_status   VARCHAR(30) DEFAULT 'paid',
    notes            TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_orders_buyer ON business.orders(buyer_id);
CREATE INDEX IF NOT EXISTS idx_orders_business ON business.orders(business_id);
CREATE INDEX IF NOT EXISTS idx_orders_status ON business.orders(status);

CREATE TABLE IF NOT EXISTS business.order_items (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id     UUID NOT NULL REFERENCES business.orders(id) ON DELETE CASCADE,
    product_id   UUID REFERENCES business.products(id) ON DELETE SET NULL,
    product_name VARCHAR(300) NOT NULL,
    product_sku  VARCHAR(100),
    image_url    TEXT,
    unit_price   DECIMAL(15,2) NOT NULL,
    quantity     INT NOT NULL CHECK (quantity > 0),
    subtotal     DECIMAL(15,2) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_order_items_order ON business.order_items(order_id);
