-- ============================================================================
-- authz/001_authz.sql — Authorization Service Schema
-- Tables: roles, permissions, role_permissions, user_roles, abac_policies
-- ============================================================================
SET search_path TO authz, core, public;

CREATE TABLE IF NOT EXISTS authz.roles (
    name        TEXT PRIMARY KEY,
    description TEXT NOT NULL DEFAULT ''
);

CREATE TABLE IF NOT EXISTS authz.permissions (
    name        TEXT PRIMARY KEY,
    description TEXT NOT NULL DEFAULT ''
);

CREATE TABLE IF NOT EXISTS authz.role_permissions (
    role_name       TEXT REFERENCES authz.roles(name) ON DELETE CASCADE,
    permission_name TEXT REFERENCES authz.permissions(name) ON DELETE CASCADE,
    PRIMARY KEY (role_name, permission_name)
);

CREATE TABLE IF NOT EXISTS authz.user_roles (
    user_id   UUID REFERENCES core.users(id) ON DELETE CASCADE,
    role_name TEXT REFERENCES authz.roles(name) ON DELETE CASCADE,
    PRIMARY KEY (user_id, role_name)
);

CREATE TABLE IF NOT EXISTS authz.abac_policies (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    action      TEXT NOT NULL,
    effect      TEXT NOT NULL CHECK (effect IN ('allow', 'deny')),
    condition   TEXT NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Seed basic system roles
INSERT INTO authz.roles (name, description) VALUES
('super_admin', 'Full platform control'),
('admin', 'Moderate users and conversations'),
('user', 'Standard chat user')
ON CONFLICT (name) DO NOTHING;
