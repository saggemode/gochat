-- A seller's Paystack subaccount (their payout bank account). The subaccount
-- code is NOT a secret; it tells Paystack where to settle the seller's share of
-- a split payment. One payout account per seller.
CREATE TABLE IF NOT EXISTS business.seller_subaccounts (
    user_id         UUID        PRIMARY KEY,
    subaccount_code TEXT        NOT NULL,
    bank_code       TEXT        NOT NULL DEFAULT '',
    account_number  TEXT        NOT NULL DEFAULT '',
    account_name    TEXT        NOT NULL DEFAULT '',
    bank_name       TEXT        NOT NULL DEFAULT '',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
