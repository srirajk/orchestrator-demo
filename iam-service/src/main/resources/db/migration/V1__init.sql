-- ============================================================
-- V1__init.sql — IAM Service schema + seed data
-- ============================================================

-- Enable pgcrypto for gen_random_uuid()
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ============================================================
-- TABLES
-- ============================================================

CREATE TABLE IF NOT EXISTS tenants (
    id                    TEXT PRIMARY KEY,
    name                  TEXT NOT NULL,
    slug                  TEXT NOT NULL UNIQUE,
    classification_schema JSONB NOT NULL DEFAULT '[]',
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS principals (
    id            TEXT PRIMARY KEY,
    tenant_id     TEXT NOT NULL REFERENCES tenants(id),
    username      TEXT NOT NULL UNIQUE,
    email         TEXT,
    password_hash TEXT NOT NULL,
    is_active     BOOLEAN NOT NULL DEFAULT TRUE,
    attributes    JSONB NOT NULL DEFAULT '{}',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS roles (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   TEXT NOT NULL REFERENCES tenants(id),
    name        TEXT NOT NULL,
    permissions JSONB NOT NULL DEFAULT '[]',
    description TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, name)
);

CREATE TABLE IF NOT EXISTS principal_roles (
    principal_id TEXT NOT NULL REFERENCES principals(id) ON DELETE CASCADE,
    role_id      UUID NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    tenant_id    TEXT NOT NULL DEFAULT 'default',
    PRIMARY KEY (principal_id, role_id)
);

CREATE TABLE IF NOT EXISTS groups (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   TEXT NOT NULL REFERENCES tenants(id),
    name        TEXT NOT NULL,
    domain_id   TEXT,
    description TEXT,
    metadata    JSONB NOT NULL DEFAULT '{}',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, name)
);

CREATE TABLE IF NOT EXISTS group_members (
    group_id     UUID NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
    principal_id TEXT NOT NULL REFERENCES principals(id) ON DELETE CASCADE,
    PRIMARY KEY (group_id, principal_id)
);

CREATE TABLE IF NOT EXISTS personal_resources (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     TEXT NOT NULL,
    principal_id  TEXT NOT NULL REFERENCES principals(id) ON DELETE CASCADE,
    resource_type TEXT NOT NULL,
    resource_id   TEXT NOT NULL,
    metadata      JSONB,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (principal_id, resource_type, resource_id)
);

CREATE TABLE IF NOT EXISTS policies (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     TEXT NOT NULL,
    name          TEXT NOT NULL,
    resource_type TEXT,
    content       TEXT NOT NULL,
    status        TEXT NOT NULL DEFAULT 'draft' CHECK (status IN ('draft', 'approved', 'deployed')),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, name)
);

CREATE TABLE IF NOT EXISTS audit_log (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      TEXT NOT NULL,
    actor_id       TEXT,
    client_id      TEXT NOT NULL DEFAULT 'system',
    action         TEXT NOT NULL,
    resource_type  TEXT,
    resource_id    TEXT,
    before_state   JSONB,
    after_state    JSONB,
    source_ip      TEXT,
    correlation_id TEXT,
    occurred_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ============================================================
-- INDEXES
-- ============================================================

CREATE INDEX IF NOT EXISTS idx_principals_tenant_id      ON principals (tenant_id);
CREATE INDEX IF NOT EXISTS idx_principals_username        ON principals (username);
CREATE INDEX IF NOT EXISTS idx_roles_tenant_id            ON roles (tenant_id);
CREATE INDEX IF NOT EXISTS idx_principal_roles_tenant     ON principal_roles (tenant_id);
CREATE INDEX IF NOT EXISTS idx_groups_tenant_id           ON groups (tenant_id);
CREATE INDEX IF NOT EXISTS idx_groups_domain_id           ON groups (domain_id);
CREATE INDEX IF NOT EXISTS idx_group_members_principal_id ON group_members (principal_id);
CREATE INDEX IF NOT EXISTS idx_personal_resources_pid_rt  ON personal_resources (principal_id, resource_type);
CREATE INDEX IF NOT EXISTS idx_policies_tenant_id         ON policies (tenant_id);
CREATE INDEX IF NOT EXISTS idx_audit_log_tenant_occurred  ON audit_log (tenant_id, occurred_at DESC);

-- ============================================================
-- SEED DATA
-- ============================================================

-- Default tenant
INSERT INTO tenants (id, name, slug, classification_schema) VALUES (
    'default',
    'Default Tenant',
    'default',
    '[
        {"name": "public",       "rank": 0},
        {"name": "internal",     "rank": 1},
        {"name": "confidential", "rank": 2},
        {"name": "restricted",   "rank": 3}
    ]'::jsonb
) ON CONFLICT DO NOTHING;

-- Roles (all in default tenant)
INSERT INTO roles (id, tenant_id, name, permissions, description) VALUES
    ('c0000000-0000-0000-0000-000000000001'::uuid, 'default', 'platform_admin',       '["*"]'::jsonb,                                           'Platform administrator with full access'),
    ('c0000000-0000-0000-0000-000000000002'::uuid, 'default', 'tenant_admin',         '["users:*","roles:*","groups:*","policies:read"]'::jsonb, 'Tenant administrator'),
    ('c0000000-0000-0000-0000-000000000003'::uuid, 'default', 'domain_admin',         '["users:read","groups:*","policies:read"]'::jsonb,         'Domain administrator'),
    ('c0000000-0000-0000-0000-000000000004'::uuid, 'default', 'relationship_manager', '["relationships:read","holdings:read","risk:read"]'::jsonb, 'Relationship manager'),
    ('c0000000-0000-0000-0000-000000000005'::uuid, 'default', 'policy_author',        '["policies:create","policies:read","policies:update"]'::jsonb, 'Policy author'),
    ('c0000000-0000-0000-0000-000000000006'::uuid, 'default', 'policy_approver',      '["policies:approve","policies:read"]'::jsonb,              'Policy approver'),
    ('c0000000-0000-0000-0000-000000000007'::uuid, 'default', 'auditor',              '["audit:read","users:read","policies:read"]'::jsonb,        'Auditor — read-only access to logs and policies'),
    ('c0000000-0000-0000-0000-000000000008'::uuid, 'default', 'member',               '[]'::jsonb,                                                'Basic member')
ON CONFLICT (tenant_id, name) DO NOTHING;

-- Principals (password_hash=SEED_REPLACE_ME — DataSeeder will update on startup)
INSERT INTO principals (id, tenant_id, username, email, password_hash, is_active, attributes) VALUES
    ('admin',        'default', 'admin',   'admin@meridian.local',   'SEED_REPLACE_ME', TRUE,
        '{"classification": "restricted", "segments": ["all"], "clearance": 3}'::jsonb),
    ('rm_jane',      'default', 'rm_jane', 'rm.jane@meridian.local', 'SEED_REPLACE_ME', TRUE,
        '{"classification": "confidential", "segments": ["wealth"], "clearance": 2, "admin_domains": []}'::jsonb),
    ('auditor_user', 'default', 'auditor', 'auditor@meridian.local', 'SEED_REPLACE_ME', TRUE,
        '{"classification": "internal", "segments": ["platform"], "clearance": 1}'::jsonb)
ON CONFLICT (username) DO NOTHING;

-- Principal → Role assignments
INSERT INTO principal_roles (principal_id, role_id, tenant_id)
SELECT 'admin', id, 'default' FROM roles WHERE name = 'platform_admin' AND tenant_id = 'default'
ON CONFLICT DO NOTHING;

INSERT INTO principal_roles (principal_id, role_id, tenant_id)
SELECT 'rm_jane', id, 'default' FROM roles WHERE name = 'relationship_manager' AND tenant_id = 'default'
ON CONFLICT DO NOTHING;

INSERT INTO principal_roles (principal_id, role_id, tenant_id)
SELECT 'auditor_user', id, 'default' FROM roles WHERE name = 'auditor' AND tenant_id = 'default'
ON CONFLICT DO NOTHING;

-- Groups (teams / domains)
INSERT INTO groups (id, tenant_id, name, domain_id, description, metadata) VALUES
    ('d0000000-0000-0000-0000-000000000001'::uuid, 'default', 'wealth-private-banking', 'wealth',   'Wealth Private Banking domain team',
        '{"defaultRoles": ["relationship_manager"], "segments": ["wealth"], "allowedDomains": ["wealth"]}'::jsonb),
    ('d0000000-0000-0000-0000-000000000002'::uuid, 'default', 'platform',               'platform', 'Platform engineering team',
        '{"defaultRoles": ["member"], "segments": ["platform"], "allowedDomains": ["platform"]}'::jsonb)
ON CONFLICT (tenant_id, name) DO NOTHING;

-- Add rm_jane to wealth-private-banking group
INSERT INTO group_members (group_id, principal_id)
SELECT 'd0000000-0000-0000-0000-000000000001'::uuid, 'rm_jane'
WHERE EXISTS (SELECT 1 FROM principals WHERE id = 'rm_jane')
ON CONFLICT DO NOTHING;

-- Personal resources for rm_jane (relationship book)
INSERT INTO personal_resources (tenant_id, principal_id, resource_type, resource_id, metadata) VALUES
    ('default', 'rm_jane', 'relationship', 'REL-00042', '{"name": "Whitman"}'::jsonb),
    ('default', 'rm_jane', 'relationship', 'REL-00099', '{"name": "Calderon Trust"}'::jsonb)
ON CONFLICT (principal_id, resource_type, resource_id) DO NOTHING;
