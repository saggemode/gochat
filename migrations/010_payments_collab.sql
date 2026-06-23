-- 010_payments_collab.sql
-- Phase 6: Payments & Wallet, Polls, Task Boards, Shared Documents

-- ── Wallets ──────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS wallets (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE UNIQUE,
    balance    DECIMAL(15,2) NOT NULL DEFAULT 0.00,
    currency   VARCHAR(3) NOT NULL DEFAULT 'USD',
    is_active  BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS wallet_transactions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    from_wallet_id  UUID REFERENCES wallets(id),
    to_wallet_id    UUID REFERENCES wallets(id),
    amount          DECIMAL(15,2) NOT NULL,
    currency        VARCHAR(3) NOT NULL DEFAULT 'USD',
    type            VARCHAR(20) NOT NULL, -- 'p2p', 'split', 'request', 'refund'
    status          VARCHAR(20) NOT NULL DEFAULT 'completed', -- 'pending', 'completed', 'failed', 'cancelled'
    description     TEXT,
    reference_id    UUID, -- links to expense_items, etc.
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_wallet_tx_from ON wallet_transactions(from_wallet_id, created_at DESC);
CREATE INDEX idx_wallet_tx_to   ON wallet_transactions(to_wallet_id, created_at DESC);

-- ── Expense Splitting ───────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS expense_groups (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id UUID NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    name            VARCHAR(200) NOT NULL,
    created_by      UUID NOT NULL REFERENCES users(id),
    is_settled      BOOLEAN DEFAULT FALSE,
    total_amount    DECIMAL(15,2) NOT NULL DEFAULT 0.00,
    currency        VARCHAR(3) NOT NULL DEFAULT 'USD',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS expense_items (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    expense_group_id UUID NOT NULL REFERENCES expense_groups(id) ON DELETE CASCADE,
    paid_by          UUID NOT NULL REFERENCES users(id),
    description      TEXT NOT NULL,
    amount           DECIMAL(15,2) NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS expense_shares (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    expense_item_id  UUID NOT NULL REFERENCES expense_items(id) ON DELETE CASCADE,
    user_id          UUID NOT NULL REFERENCES users(id),
    share_amount     DECIMAL(15,2) NOT NULL,
    is_settled       BOOLEAN DEFAULT FALSE,
    settled_at       TIMESTAMPTZ,
    UNIQUE(expense_item_id, user_id)
);

-- ── Polls ────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS polls (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id UUID NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    created_by      UUID NOT NULL REFERENCES users(id),
    question        TEXT NOT NULL,
    is_anonymous    BOOLEAN DEFAULT FALSE,
    is_multiple     BOOLEAN DEFAULT FALSE,
    is_closed       BOOLEAN DEFAULT FALSE,
    expires_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS poll_options (
    id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    poll_id   UUID NOT NULL REFERENCES polls(id) ON DELETE CASCADE,
    text      VARCHAR(500) NOT NULL,
    sort_order INT DEFAULT 0
);

CREATE TABLE IF NOT EXISTS poll_votes (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    poll_id    UUID NOT NULL REFERENCES polls(id) ON DELETE CASCADE,
    option_id  UUID NOT NULL REFERENCES poll_options(id) ON DELETE CASCADE,
    user_id    UUID NOT NULL REFERENCES users(id),
    voted_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(poll_id, option_id, user_id)
);

-- ── Task Boards ─────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS task_boards (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id UUID NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    name            VARCHAR(200) NOT NULL,
    created_by      UUID NOT NULL REFERENCES users(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS tasks (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    board_id      UUID NOT NULL REFERENCES task_boards(id) ON DELETE CASCADE,
    title         VARCHAR(500) NOT NULL,
    description   TEXT,
    status        VARCHAR(20) NOT NULL DEFAULT 'todo', -- 'todo', 'in_progress', 'done'
    priority      VARCHAR(10) DEFAULT 'medium',        -- 'low', 'medium', 'high', 'urgent'
    assigned_to   UUID REFERENCES users(id),
    due_date      TIMESTAMPTZ,
    created_by    UUID NOT NULL REFERENCES users(id),
    sort_order    INT DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ── Shared Documents ────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS shared_docs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id UUID NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    title           VARCHAR(500) NOT NULL,
    content         TEXT NOT NULL DEFAULT '',
    created_by      UUID NOT NULL REFERENCES users(id),
    version         INT NOT NULL DEFAULT 1,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS shared_doc_edits (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    doc_id     UUID NOT NULL REFERENCES shared_docs(id) ON DELETE CASCADE,
    user_id    UUID NOT NULL REFERENCES users(id),
    diff_text  TEXT NOT NULL,
    version    INT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
