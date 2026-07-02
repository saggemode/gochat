-- ============================================================================
-- business/001_business.sql — Business Service Schema
-- Tables: business_profiles, product_catalogs, product_items,
--         appointments, appointment_bookings, auto_replies,
--         customer_queues, themes, user_themes, accessibility_settings
-- ============================================================================
SET search_path TO business, core, public;

-- ── Business Profiles ───────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS business.business_profiles (
    user_id         UUID PRIMARY KEY REFERENCES core.users(id) ON DELETE CASCADE,
    business_name   VARCHAR(300) NOT NULL,
    category        VARCHAR(100),
    description     TEXT,
    address         TEXT,
    website         TEXT,
    email           VARCHAR(255),
    phone           VARCHAR(50),
    hours_json      JSONB DEFAULT '{}',
    is_verified     BOOLEAN DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ── Product Catalogs ────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS business.product_catalogs (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID NOT NULL REFERENCES core.users(id) ON DELETE CASCADE,
    name       VARCHAR(200) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS business.product_items (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    catalog_id  UUID NOT NULL REFERENCES business.product_catalogs(id) ON DELETE CASCADE,
    name        VARCHAR(300) NOT NULL,
    description TEXT,
    price       DECIMAL(15,2) NOT NULL,
    currency    VARCHAR(3) DEFAULT 'USD',
    image_url   TEXT,
    in_stock    BOOLEAN DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ── Appointments ────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS business.appointments (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id     UUID NOT NULL REFERENCES core.users(id) ON DELETE CASCADE,
    title           VARCHAR(300) NOT NULL,
    description     TEXT,
    start_time      TIMESTAMPTZ NOT NULL,
    end_time        TIMESTAMPTZ NOT NULL,
    max_bookings    INT DEFAULT 1,
    current_bookings INT DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS business.appointment_bookings (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    appointment_id UUID NOT NULL REFERENCES business.appointments(id) ON DELETE CASCADE,
    user_id        UUID NOT NULL REFERENCES core.users(id) ON DELETE CASCADE,
    status         VARCHAR(20) DEFAULT 'confirmed',
    notes          TEXT,
    booked_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(appointment_id, user_id)
);

-- ── Auto-Reply Rules ────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS business.auto_replies (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID NOT NULL REFERENCES core.users(id) ON DELETE CASCADE,
    trigger_type  VARCHAR(30) NOT NULL,
    trigger_value VARCHAR(200),
    reply_text    TEXT NOT NULL,
    is_active     BOOLEAN DEFAULT TRUE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ── Customer Queue ──────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS business.customer_queues (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id UUID NOT NULL REFERENCES core.users(id) ON DELETE CASCADE,
    customer_id UUID NOT NULL REFERENCES core.users(id),
    position    INT NOT NULL,
    status      VARCHAR(20) DEFAULT 'waiting',
    joined_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    served_at   TIMESTAMPTZ
);

-- ── Themes ──────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS business.themes (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(200) NOT NULL,
    created_by      UUID REFERENCES core.users(id),
    primary_color   VARCHAR(7) NOT NULL,
    secondary_color VARCHAR(7) NOT NULL,
    bg_color        VARCHAR(7) NOT NULL,
    text_color      VARCHAR(7) NOT NULL,
    font_family     VARCHAR(100) DEFAULT 'Inter',
    is_public       BOOLEAN DEFAULT FALSE,
    is_dark         BOOLEAN DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS business.user_themes (
    user_id    UUID PRIMARY KEY REFERENCES core.users(id) ON DELETE CASCADE,
    theme_id   UUID NOT NULL REFERENCES business.themes(id),
    applied_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ── Accessibility Settings ──────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS business.accessibility_settings (
    user_id             UUID PRIMARY KEY REFERENCES core.users(id) ON DELETE CASCADE,
    font_scale          FLOAT DEFAULT 1.0,
    high_contrast       BOOLEAN DEFAULT FALSE,
    reduce_motion       BOOLEAN DEFAULT FALSE,
    screen_reader_mode  BOOLEAN DEFAULT FALSE,
    dyslexia_font       BOOLEAN DEFAULT FALSE,
    auto_caption_voice  BOOLEAN DEFAULT FALSE,
    speech_to_text      BOOLEAN DEFAULT FALSE,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
