-- ============================================================================
-- business/028_wallet_redpackets.sql — Wallet + red packets (hóngbāo)
-- Amounts are stored in MINOR units (kobo/cents). Red packets are pure wallet
-- ledger operations; real money only enters via Paystack top-up and leaves via
-- a (future, not-yet-built) withdrawal. Idempotent.
-- ============================================================================
SET search_path TO business, core, public;

CREATE TABLE IF NOT EXISTS business.wallets (
    user_id    UUID PRIMARY KEY REFERENCES core.users(id) ON DELETE CASCADE,
    balance    BIGINT NOT NULL DEFAULT 0,
    currency   TEXT   NOT NULL DEFAULT 'NGN',
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Append-only ledger of every balance change (audit + idempotency via reference).
CREATE TABLE IF NOT EXISTS business.wallet_ledger (
    id         UUID   PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID   NOT NULL REFERENCES core.users(id) ON DELETE CASCADE,
    amount     BIGINT NOT NULL,              -- +credit / -debit, minor units
    kind       TEXT   NOT NULL,              -- topup | redpacket_send | redpacket_claim | withdrawal
    reference  TEXT   NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_wallet_ledger_user ON business.wallet_ledger(user_id, created_at DESC);
-- A Paystack top-up reference may only credit a wallet once.
CREATE UNIQUE INDEX IF NOT EXISTS uq_wallet_ledger_topup_ref
    ON business.wallet_ledger(reference) WHERE kind = 'topup' AND reference <> '';

CREATE TABLE IF NOT EXISTS business.red_packets (
    id               UUID   PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id  UUID   NOT NULL,
    sender_id        UUID   NOT NULL REFERENCES core.users(id) ON DELETE CASCADE,
    total_amount     BIGINT NOT NULL,
    remaining_amount BIGINT NOT NULL,
    total_count      INT    NOT NULL,
    remaining_count  INT    NOT NULL,
    message          TEXT   NOT NULL DEFAULT '',
    currency         TEXT   NOT NULL DEFAULT 'NGN',
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_red_packets_conv ON business.red_packets(conversation_id, created_at DESC);

CREATE TABLE IF NOT EXISTS business.red_packet_claims (
    packet_id  UUID   NOT NULL REFERENCES business.red_packets(id) ON DELETE CASCADE,
    user_id    UUID   NOT NULL REFERENCES core.users(id) ON DELETE CASCADE,
    amount     BIGINT NOT NULL,
    claimed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (packet_id, user_id)
);
