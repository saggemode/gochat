-- ============================================================================
-- authz/002_admin_rbac.sql — Admin console roles & permissions
-- Tiered staff for the admin panel. Idempotent (ON CONFLICT DO NOTHING), runs
-- every boot under the authz service's AutoMigrate.
--
-- Permission names ARE the `action` strings passed to Authorize(). super_admin
-- bypasses all checks in code; it is also granted "*" here for clarity.
-- ============================================================================
SET search_path TO authz, core, public;

-- Admin permissions ('*' first: role_permissions references it below).
INSERT INTO authz.permissions (name, description) VALUES
    ('*',                      'All permissions'),
    ('admin:access',           'Open the admin console'),
    ('admin:users:write',      'Suspend / ban / reactivate users'),
    ('admin:disputes:resolve', 'Resolve or reject marketplace disputes'),
    ('admin:reports:action',   'Action or dismiss moderation reports'),
    ('admin:finance:read',     'View revenue, GMV, orders and payouts'),
    ('admin:roles:write',      'Create accounts and assign roles (super admin)')
ON CONFLICT (name) DO NOTHING;

-- Staff roles (admin/super_admin/user are seeded by 001_authz.sql).
INSERT INTO authz.roles (name, description) VALUES
    ('support',   'Reads the console and actions reports'),
    ('finance',   'Reads financial data (revenue, orders, payouts)'),
    ('moderator', 'Moderates users and reports')
ON CONFLICT (name) DO NOTHING;

-- Role → permission grants.
INSERT INTO authz.role_permissions (role_name, permission_name) VALUES
    -- super_admin: explicit wildcard (also bypasses in code)
    ('super_admin', '*'),
    -- admin: every admin permission
    ('admin', 'admin:access'),
    ('admin', 'admin:users:write'),
    ('admin', 'admin:disputes:resolve'),
    ('admin', 'admin:reports:action'),
    ('admin', 'admin:finance:read'),
    -- support: view + action reports
    ('support', 'admin:access'),
    ('support', 'admin:reports:action'),
    -- finance: view + financial data
    ('finance', 'admin:access'),
    ('finance', 'admin:finance:read'),
    -- moderator: view + ban users + action reports + resolve disputes
    ('moderator', 'admin:access'),
    ('moderator', 'admin:users:write'),
    ('moderator', 'admin:reports:action'),
    ('moderator', 'admin:disputes:resolve')
ON CONFLICT (role_name, permission_name) DO NOTHING;
