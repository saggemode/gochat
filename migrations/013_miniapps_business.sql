-- 013_miniapps_business.sql
-- Phase 9: Mini-Apps Platform, Business Suite, Developer Tools, Accessibility

-- ── Bots ─────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS bots (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    username     VARCHAR(100) NOT NULL UNIQUE,
    display_name VARCHAR(200) NOT NULL,
    description  TEXT,
    avatar_url   TEXT,
    webhook_url  TEXT,
    is_active    BOOLEAN DEFAULT TRUE,
    is_verified  BOOLEAN DEFAULT FALSE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS bot_commands (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    bot_id      UUID NOT NULL REFERENCES bots(id) ON DELETE CASCADE,
    command     VARCHAR(100) NOT NULL, -- e.g. '/weather'
    description VARCHAR(500),
    UNIQUE(bot_id, command)
);

-- ── Mini-Apps ───────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS miniapps (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    developer_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name         VARCHAR(200) NOT NULL,
    description  TEXT,
    icon_url     TEXT,
    manifest_url TEXT NOT NULL,
    category     VARCHAR(50), -- 'game', 'utility', 'shopping', 'booking', 'social'
    is_approved  BOOLEAN DEFAULT FALSE,
    install_count INT DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS miniapp_sessions (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    miniapp_id  UUID NOT NULL REFERENCES miniapps(id) ON DELETE CASCADE,
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    conversation_id UUID REFERENCES conversations(id),
    session_data JSONB DEFAULT '{}',
    is_active   BOOLEAN DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ── Developer Webhooks ──────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS webhooks (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    url        TEXT NOT NULL,
    events     TEXT[] NOT NULL, -- array of event types to subscribe to
    secret     VARCHAR(255) NOT NULL,
    is_active  BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ── API Keys ────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS api_keys (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    key_hash    VARCHAR(255) NOT NULL,
    name        VARCHAR(200) NOT NULL,
    permissions TEXT[] DEFAULT '{}',
    last_used   TIMESTAMPTZ,
    expires_at  TIMESTAMPTZ,
    is_active   BOOLEAN DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ── Business Profiles ───────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS business_profiles (
    user_id         UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    business_name   VARCHAR(300) NOT NULL,
    category        VARCHAR(100),
    description     TEXT,
    address         TEXT,
    website         TEXT,
    email           VARCHAR(255),
    phone           VARCHAR(50),
    hours_json      JSONB DEFAULT '{}', -- business hours per day
    is_verified     BOOLEAN DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ── Product Catalogs ────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS product_catalogs (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name       VARCHAR(200) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS product_items (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    catalog_id  UUID NOT NULL REFERENCES product_catalogs(id) ON DELETE CASCADE,
    name        VARCHAR(300) NOT NULL,
    description TEXT,
    price       DECIMAL(15,2) NOT NULL,
    currency    VARCHAR(3) DEFAULT 'USD',
    image_url   TEXT,
    in_stock    BOOLEAN DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ── Appointments ────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS appointments (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title       VARCHAR(300) NOT NULL,
    description TEXT,
    start_time  TIMESTAMPTZ NOT NULL,
    end_time    TIMESTAMPTZ NOT NULL,
    max_bookings INT DEFAULT 1,
    current_bookings INT DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS appointment_bookings (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    appointment_id UUID NOT NULL REFERENCES appointments(id) ON DELETE CASCADE,
    user_id        UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    status         VARCHAR(20) DEFAULT 'confirmed', -- 'confirmed', 'cancelled', 'completed'
    notes          TEXT,
    booked_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(appointment_id, user_id)
);

-- ── Auto-Reply Rules ────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS auto_replies (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    trigger_type VARCHAR(30) NOT NULL, -- 'keyword', 'after_hours', 'first_message', 'always'
    trigger_value VARCHAR(200), -- keyword for 'keyword' type
    reply_text   TEXT NOT NULL,
    is_active    BOOLEAN DEFAULT TRUE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ── Customer Queue ──────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS customer_queues (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    customer_id UUID NOT NULL REFERENCES users(id),
    position    INT NOT NULL,
    status      VARCHAR(20) DEFAULT 'waiting', -- 'waiting', 'serving', 'completed'
    joined_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    served_at   TIMESTAMPTZ
);

-- ── Themes ──────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS themes (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(200) NOT NULL,
    created_by      UUID REFERENCES users(id),
    primary_color   VARCHAR(7) NOT NULL,
    secondary_color VARCHAR(7) NOT NULL,
    bg_color        VARCHAR(7) NOT NULL,
    text_color      VARCHAR(7) NOT NULL,
    font_family     VARCHAR(100) DEFAULT 'Inter',
    is_public       BOOLEAN DEFAULT FALSE,
    is_dark         BOOLEAN DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS user_themes (
    user_id  UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    theme_id UUID NOT NULL REFERENCES themes(id),
    applied_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ── Accessibility Settings ──────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS accessibility_settings (
    user_id             UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    font_scale          FLOAT DEFAULT 1.0,
    high_contrast       BOOLEAN DEFAULT FALSE,
    reduce_motion       BOOLEAN DEFAULT FALSE,
    screen_reader_mode  BOOLEAN DEFAULT FALSE,
    dyslexia_font       BOOLEAN DEFAULT FALSE,
    auto_caption_voice  BOOLEAN DEFAULT FALSE,
    speech_to_text      BOOLEAN DEFAULT FALSE,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
