-- ============================================================================
-- business/002_marketplace.sql — E-Commerce Marketplace Schema
-- Tables: categories, sub_categories, brands, products, product_images,
--         reviews, product_views
-- Alters: business_profiles (add marketplace fields)
-- ============================================================================
SET search_path TO business, core, public;

-- ── Categories & Subcategories ──────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS business.categories (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name       VARCHAR(100) NOT NULL UNIQUE,
    icon       VARCHAR(10),
    sort_order INT DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS business.sub_categories (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    category_id UUID NOT NULL REFERENCES business.categories(id) ON DELETE CASCADE,
    name        VARCHAR(100) NOT NULL,
    sort_order  INT DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(category_id, name)
);

-- ── Brands ──────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS business.brands (
    id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name     VARCHAR(200) NOT NULL UNIQUE,
    logo_url TEXT
);

-- ── Enhance Business Profiles ───────────────────────────────────────────────

ALTER TABLE business.business_profiles
    ADD COLUMN IF NOT EXISTS logo_url     TEXT,
    ADD COLUMN IF NOT EXISTS banner_url   TEXT,
    ADD COLUMN IF NOT EXISTS state        VARCHAR(100),
    ADD COLUMN IF NOT EXISTS country_code VARCHAR(5),
    ADD COLUMN IF NOT EXISTS slug         VARCHAR(200) UNIQUE;

-- ── Products (full e-commerce) ──────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS business.products (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id      UUID NOT NULL REFERENCES business.business_profiles(user_id) ON DELETE CASCADE,
    owner_id         UUID NOT NULL REFERENCES core.users(id) ON DELETE CASCADE,
    name             VARCHAR(300) NOT NULL,
    description      TEXT,
    category_id      UUID REFERENCES business.categories(id),
    sub_category_id  UUID REFERENCES business.sub_categories(id),
    brand_id         UUID REFERENCES business.brands(id),
    price            DECIMAL(15,2) NOT NULL,
    discount_percent DECIMAL(5,2) DEFAULT 0,
    currency         VARCHAR(5) DEFAULT 'USD',
    quantity         INT DEFAULT 100,
    sku              VARCHAR(100),
    color            VARCHAR(50),
    size             VARCHAR(50),
    weight           DECIMAL(10,2),
    shipping_fee     DECIMAL(10,2) DEFAULT 0,
    is_published     BOOLEAN DEFAULT TRUE,
    view_count       INT DEFAULT 0,
    order_count      INT DEFAULT 0,
    rating_avg       DECIMAL(3,2) DEFAULT 0,
    review_count     INT DEFAULT 0,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_products_business ON business.products(business_id);
CREATE INDEX IF NOT EXISTS idx_products_category ON business.products(category_id);
CREATE INDEX IF NOT EXISTS idx_products_published ON business.products(is_published) WHERE is_published = TRUE;
CREATE INDEX IF NOT EXISTS idx_products_ranking ON business.products(order_count DESC, view_count DESC, rating_avg DESC);

-- ── Product Images ──────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS business.product_images (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id UUID NOT NULL REFERENCES business.products(id) ON DELETE CASCADE,
    url        TEXT NOT NULL,
    sort_order INT DEFAULT 0,
    is_primary BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_product_images_product ON business.product_images(product_id);

-- ── Reviews ─────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS business.reviews (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id UUID NOT NULL REFERENCES business.products(id) ON DELETE CASCADE,
    user_id    UUID NOT NULL REFERENCES core.users(id) ON DELETE CASCADE,
    rating     SMALLINT NOT NULL CHECK (rating >= 1 AND rating <= 5),
    comment    TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(product_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_reviews_product ON business.reviews(product_id);

-- ── Product Views (analytics) ───────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS business.product_views (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id UUID NOT NULL REFERENCES business.products(id) ON DELETE CASCADE,
    user_id    UUID REFERENCES core.users(id) ON DELETE SET NULL,
    viewed_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_product_views_product ON business.product_views(product_id);

-- ── Seed Default Categories ────────────────────────────────────────────────

INSERT INTO business.categories (name, icon, sort_order) VALUES
    ('Electronics',      '📱', 1),
    ('Fashion',          '👗', 2),
    ('Furniture',        '🪑', 3),
    ('Food & Drinks',    '🍔', 4),
    ('Health & Beauty',  '💄', 5),
    ('Home & Garden',    '🏠', 6),
    ('Sports',           '⚽', 7),
    ('Books & Media',    '📚', 8),
    ('Vehicles',         '🚗', 9),
    ('Services',         '🛠️', 10),
    ('Other',            '📦', 11)
ON CONFLICT (name) DO NOTHING;

-- Subcategories
INSERT INTO business.sub_categories (category_id, name, sort_order)
SELECT c.id, s.name, s.sort_order
FROM business.categories c
CROSS JOIN LATERAL (VALUES
    ('Electronics', 'Phones', 1), ('Electronics', 'Laptops', 2), ('Electronics', 'TV', 3), ('Electronics', 'Accessories', 4),
    ('Fashion', 'Shoes', 1), ('Fashion', 'Shirts', 2), ('Fashion', 'Bags', 3), ('Fashion', 'Jewelry', 4),
    ('Furniture', 'Chairs', 1), ('Furniture', 'Tables', 2), ('Furniture', 'Beds', 3), ('Furniture', 'Storage', 4)
) AS s(cat, name, sort_order)
WHERE c.name = s.cat
ON CONFLICT (category_id, name) DO NOTHING;
