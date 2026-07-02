-- ============================================================================
-- payment/001_payment.sql — Payment Service Schema
-- Tables: wallets, wallet_transactions, expense_groups, expense_items,
--         expense_shares, polls, poll_options, poll_votes,
--         task_boards, tasks, shared_docs, shared_doc_edits
-- ============================================================================
SET search_path TO payment, core, public;

-- ── Wallets ─────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS payment.wallets (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID NOT NULL REFERENCES core.users(id) ON DELETE CASCADE UNIQUE,
    balance    DECIMAL(15,2) NOT NULL DEFAULT 0.00,
    currency   VARCHAR(3) NOT NULL DEFAULT 'USD',
    is_active  BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS payment.wallet_transactions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    from_wallet_id  UUID REFERENCES payment.wallets(id),
    to_wallet_id    UUID REFERENCES payment.wallets(id),
    amount          DECIMAL(15,2) NOT NULL,
    currency        VARCHAR(3) NOT NULL DEFAULT 'USD',
    type            VARCHAR(20) NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'completed',
    description     TEXT,
    reference_id    UUID,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_wallet_tx_from ON payment.wallet_transactions(from_wallet_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_wallet_tx_to   ON payment.wallet_transactions(to_wallet_id, created_at DESC);

-- ── Expense Splitting ───────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS payment.expense_groups (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id UUID NOT NULL,
    name            VARCHAR(200) NOT NULL,
    created_by      UUID NOT NULL REFERENCES core.users(id),
    is_settled      BOOLEAN DEFAULT FALSE,
    total_amount    DECIMAL(15,2) NOT NULL DEFAULT 0.00,
    currency        VARCHAR(3) NOT NULL DEFAULT 'USD',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS payment.expense_items (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    expense_group_id UUID NOT NULL REFERENCES payment.expense_groups(id) ON DELETE CASCADE,
    paid_by          UUID NOT NULL REFERENCES core.users(id),
    description      TEXT NOT NULL,
    amount           DECIMAL(15,2) NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS payment.expense_shares (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    expense_item_id  UUID NOT NULL REFERENCES payment.expense_items(id) ON DELETE CASCADE,
    user_id          UUID NOT NULL REFERENCES core.users(id),
    share_amount     DECIMAL(15,2) NOT NULL,
    is_settled       BOOLEAN DEFAULT FALSE,
    settled_at       TIMESTAMPTZ,
    UNIQUE(expense_item_id, user_id)
);

-- ── Polls ────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS payment.polls (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id UUID NOT NULL,
    created_by      UUID NOT NULL REFERENCES core.users(id),
    question        TEXT NOT NULL,
    is_anonymous    BOOLEAN DEFAULT FALSE,
    is_multiple     BOOLEAN DEFAULT FALSE,
    is_closed       BOOLEAN DEFAULT FALSE,
    expires_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS payment.poll_options (
    id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    poll_id   UUID NOT NULL REFERENCES payment.polls(id) ON DELETE CASCADE,
    text      VARCHAR(500) NOT NULL,
    sort_order INT DEFAULT 0
);

CREATE TABLE IF NOT EXISTS payment.poll_votes (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    poll_id    UUID NOT NULL REFERENCES payment.polls(id) ON DELETE CASCADE,
    option_id  UUID NOT NULL REFERENCES payment.poll_options(id) ON DELETE CASCADE,
    user_id    UUID NOT NULL REFERENCES core.users(id),
    voted_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(poll_id, option_id, user_id)
);

-- ── Task Boards ─────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS payment.task_boards (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id UUID NOT NULL,
    name            VARCHAR(200) NOT NULL,
    created_by      UUID NOT NULL REFERENCES core.users(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS payment.tasks (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    board_id      UUID NOT NULL REFERENCES payment.task_boards(id) ON DELETE CASCADE,
    title         VARCHAR(500) NOT NULL,
    description   TEXT,
    status        VARCHAR(20) NOT NULL DEFAULT 'todo',
    priority      VARCHAR(10) DEFAULT 'medium',
    assigned_to   UUID REFERENCES core.users(id),
    due_date      TIMESTAMPTZ,
    created_by    UUID NOT NULL REFERENCES core.users(id),
    sort_order    INT DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ── Shared Documents ────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS payment.shared_docs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id UUID NOT NULL,
    title           VARCHAR(500) NOT NULL,
    content         TEXT NOT NULL DEFAULT '',
    created_by      UUID NOT NULL REFERENCES core.users(id),
    version         INT NOT NULL DEFAULT 1,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS payment.shared_doc_edits (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    doc_id     UUID NOT NULL REFERENCES payment.shared_docs(id) ON DELETE CASCADE,
    user_id    UUID NOT NULL REFERENCES core.users(id),
    diff_text  TEXT NOT NULL,
    version    INT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
