-- ============================================================================
-- grp/002_bot.sql — Bot Configuration Schema
-- ============================================================================
SET search_path TO grp, core, public;

-- ── Bot Configuration ────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS grp.bot_configs (
    group_id              UUID        PRIMARY KEY,
    bot_id                UUID        NOT NULL,
    permissions           INT[]       NOT NULL DEFAULT '{}',
    is_active             BOOLEAN     NOT NULL DEFAULT TRUE,
    commands_prefix       TEXT        NOT NULL DEFAULT '/',
    rules                 TEXT        NOT NULL DEFAULT '',
    welcome_message       TEXT        NOT NULL DEFAULT '',
    spam_protection_level INT         NOT NULL DEFAULT 1
);

CREATE INDEX IF NOT EXISTS idx_bot_configs_bot ON grp.bot_configs(bot_id);

-- ── Audit Logs ──────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS grp.audit_logs (
    id              UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    conversation_id UUID        NOT NULL,
    actor_id        UUID        NOT NULL,
    action_type     TEXT        NOT NULL,
    target_id       UUID,
    reason          TEXT        NOT NULL DEFAULT '',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_audit_logs_conv ON grp.audit_logs(conversation_id);
