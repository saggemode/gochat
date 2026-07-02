-- ============================================================================
-- miniapp/001_miniapp.sql — MiniApp Service Schema
-- Tables: bots, bot_commands, miniapps, miniapp_sessions,
--         webhooks, api_keys
-- ============================================================================
SET search_path TO miniapp, core, public;

-- ── Bots ────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS miniapp.bots (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id     UUID NOT NULL REFERENCES core.users(id) ON DELETE CASCADE,
    username     VARCHAR(100) NOT NULL UNIQUE,
    display_name VARCHAR(200) NOT NULL,
    description  TEXT,
    avatar_url   TEXT,
    webhook_url  TEXT,
    is_active    BOOLEAN DEFAULT TRUE,
    is_verified  BOOLEAN DEFAULT FALSE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS miniapp.bot_commands (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    bot_id      UUID NOT NULL REFERENCES miniapp.bots(id) ON DELETE CASCADE,
    command     VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    UNIQUE(bot_id, command)
);

-- ── Mini-Apps ───────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS miniapp.miniapps (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    developer_id UUID NOT NULL REFERENCES core.users(id) ON DELETE CASCADE,
    name         VARCHAR(200) NOT NULL,
    description  TEXT,
    icon_url     TEXT,
    manifest_url TEXT NOT NULL,
    category     VARCHAR(50),
    is_approved  BOOLEAN DEFAULT FALSE,
    install_count INT DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS miniapp.miniapp_sessions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    miniapp_id      UUID NOT NULL REFERENCES miniapp.miniapps(id) ON DELETE CASCADE,
    user_id         UUID NOT NULL REFERENCES core.users(id) ON DELETE CASCADE,
    conversation_id UUID,
    session_data    JSONB DEFAULT '{}',
    is_active       BOOLEAN DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ── Developer Webhooks ──────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS miniapp.webhooks (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID NOT NULL REFERENCES core.users(id) ON DELETE CASCADE,
    url        TEXT NOT NULL,
    events     TEXT[] NOT NULL,
    secret     VARCHAR(255) NOT NULL,
    is_active  BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ── API Keys ────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS miniapp.api_keys (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES core.users(id) ON DELETE CASCADE,
    key_hash    VARCHAR(255) NOT NULL,
    name        VARCHAR(200) NOT NULL,
    permissions TEXT[] DEFAULT '{}',
    last_used   TIMESTAMPTZ,
    expires_at  TIMESTAMPTZ,
    is_active   BOOLEAN DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
