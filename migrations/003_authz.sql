-- Migration 003: Authz RBAC & ABAC Schema
-- +goose Up

CREATE TABLE IF NOT EXISTS roles (
    name        TEXT PRIMARY KEY,
    description TEXT NOT NULL DEFAULT ''
);

CREATE TABLE IF NOT EXISTS permissions (
    name        TEXT PRIMARY KEY,
    description TEXT NOT NULL DEFAULT ''
);

CREATE TABLE IF NOT EXISTS role_permissions (
    role_name       TEXT REFERENCES roles(name) ON DELETE CASCADE,
    permission_name TEXT REFERENCES permissions(name) ON DELETE CASCADE,
    PRIMARY KEY (role_name, permission_name)
);

CREATE TABLE IF NOT EXISTS user_roles (
    user_id   UUID REFERENCES users(id) ON DELETE CASCADE,
    role_name TEXT REFERENCES roles(name) ON DELETE CASCADE,
    PRIMARY KEY (user_id, role_name)
);

CREATE TABLE IF NOT EXISTS abac_policies (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    action      TEXT NOT NULL,
    effect      TEXT NOT NULL CHECK (effect IN ('allow', 'deny')),
    condition   TEXT NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Seed basic system roles
INSERT INTO roles (name, description) VALUES
('super_admin', 'Full platform control'),
('admin', 'Moderate users and conversations'),
('user', 'Standard chat user')
ON CONFLICT (name) DO NOTHING;

