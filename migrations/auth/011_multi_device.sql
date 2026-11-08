-- ============================================================================
-- auth/011_multi_device.sql — per-device E2EE keys (multi-device Phase 1)
-- Each (user, device) has its own identity + signed prekey + one-time prekeys.
-- Additive: the legacy per-user core.users.prekey_* and core.user_prekeys stay
-- for back-compat (treated as device "1"). One-time prekeys are APPEND-ONLY.
-- ============================================================================
SET search_path TO core, public;

CREATE TABLE IF NOT EXISTS core.user_devices (
    user_id                 UUID        NOT NULL REFERENCES core.users(id) ON DELETE CASCADE,
    device_id               TEXT        NOT NULL,
    identity_key            TEXT        NOT NULL DEFAULT '',
    signed_prekey           TEXT        NOT NULL DEFAULT '',
    signed_prekey_signature TEXT        NOT NULL DEFAULT '',
    registration_id         INT         NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_seen               TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, device_id)
);

CREATE TABLE IF NOT EXISTS core.device_prekeys (
    user_id    UUID        NOT NULL REFERENCES core.users(id) ON DELETE CASCADE,
    device_id  TEXT        NOT NULL,
    key_id     INT         NOT NULL,
    public_key TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, device_id, key_id)
);
CREATE INDEX IF NOT EXISTS idx_device_prekeys_lookup ON core.device_prekeys(user_id, device_id);
