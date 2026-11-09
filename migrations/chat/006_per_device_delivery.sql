-- ============================================================================
-- chat/006_per_device_delivery.sql — multi-device delete-after-delivery (Phase 5)
--
-- Makes delivery tracking device-aware WITHOUT changing single-device behavior.
-- Adds a device_id to each delivery row (sentinel '' = user-level / device-unknown
-- ack, the legacy case) and repoints the primary key to include it, so a message
-- can be acknowledged once per (recipient, device).
--
-- SAFETY: the purge query (see PurgeFullyDelivered in message_repo.go) treats a
-- recipient with 0 or 1 registered device EXACTLY as before — any delivery row
-- satisfies them — so purge behavior for today's single-device accounts is
-- unchanged. The per-device requirement only tightens for recipients who have
-- 2+ registered devices (dormant until multi-device is in use).
-- ============================================================================
SET search_path TO chat, core, public;

ALTER TABLE chat.message_deliveries
    ADD COLUMN IF NOT EXISTS device_id TEXT NOT NULL DEFAULT '';

-- Repoint the primary key to (message_id, user_id, device_id). Idempotent: drop
-- the old PK (whatever its name) if present, then add the composite one. Existing
-- rows already carry device_id = '' from the column default.
ALTER TABLE chat.message_deliveries DROP CONSTRAINT IF EXISTS message_deliveries_pkey;
ALTER TABLE chat.message_deliveries ADD  CONSTRAINT message_deliveries_pkey
    PRIMARY KEY (message_id, user_id, device_id);
