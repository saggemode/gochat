-- ============================================================================
-- grp/001_grp.sql — Group Service Schema
-- Tables: group_metadata, group_join_approvals, communities,
--         community_groups, broadcast_lists, broadcast_recipients
-- ============================================================================
SET search_path TO grp, core, public;

-- ── Group Metadata ──────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS grp.group_metadata (
    conversation_id        UUID        PRIMARY KEY,
    description            TEXT        NOT NULL DEFAULT '',
    announcements_only     BOOLEAN     NOT NULL DEFAULT FALSE,
    admins_only_edit_info  BOOLEAN     NOT NULL DEFAULT TRUE,
    invite_code            TEXT        UNIQUE,
    join_approval_required BOOLEAN     NOT NULL DEFAULT FALSE
);

-- ── Group Join Approvals ────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS grp.group_join_approvals (
    id              UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    conversation_id UUID        NOT NULL,
    user_id         UUID        NOT NULL REFERENCES core.users(id) ON DELETE CASCADE,
    status          TEXT        NOT NULL DEFAULT 'pending',
    requested_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    resolved_by     UUID        REFERENCES core.users(id),
    resolved_at     TIMESTAMPTZ,
    CONSTRAINT unique_pending_request UNIQUE (conversation_id, user_id, status)
);

CREATE INDEX IF NOT EXISTS idx_group_join_approvals_conversation
    ON grp.group_join_approvals(conversation_id);

-- ── Communities ─────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS grp.communities (
    id          UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    name        TEXT        NOT NULL,
    description TEXT        NOT NULL DEFAULT '',
    created_by  UUID        NOT NULL REFERENCES core.users(id) ON DELETE CASCADE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_communities_creator ON grp.communities(created_by);

-- ── Community Groups ────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS grp.community_groups (
    community_id    UUID NOT NULL REFERENCES grp.communities(id) ON DELETE CASCADE,
    conversation_id UUID NOT NULL,
    PRIMARY KEY (community_id, conversation_id)
);

-- ── Broadcast Lists ─────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS grp.broadcast_lists (
    id         UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    owner_id   UUID        NOT NULL REFERENCES core.users(id) ON DELETE CASCADE,
    name       TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_broadcast_lists_owner ON grp.broadcast_lists(owner_id);

-- ── Broadcast Recipients ────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS grp.broadcast_recipients (
    broadcast_list_id UUID NOT NULL REFERENCES grp.broadcast_lists(id) ON DELETE CASCADE,
    user_id           UUID NOT NULL REFERENCES core.users(id) ON DELETE CASCADE,
    PRIMARY KEY (broadcast_list_id, user_id)
);
