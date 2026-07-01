-- ============================================================================
-- V2__seed_demo_data.sql
-- Rich demo seed data — personas, groups, relationships, policies, audit trail
-- Makes the admin UI immediately understandable and tells the authz story:
--   rm_jane has Whitman + Calderon | rm_carlos has Okafor + Sterling
--   Ask about Okafor as Jane → denied. That's the live demo.
-- ============================================================================

-- ── Update classification schema with richer metadata ────────────────────────
UPDATE tenants
SET classification_schema = '[
  {"name": "public",       "rank": 0, "description": "No restrictions, anyone can access"},
  {"name": "internal",     "rank": 1, "description": "Internal staff only"},
  {"name": "confidential", "rank": 2, "description": "Relationship managers and above"},
  {"name": "restricted",   "rank": 3, "description": "Senior staff only — PII, trading data"}
]'::jsonb
WHERE id = 'default';

-- ── Additional roles with descriptive permissions ────────────────────────────
-- (basic roles already seeded in V1; just ensure descriptions exist)
UPDATE roles SET description = 'Full platform access — no restrictions'                          WHERE name = 'platform_admin'     AND tenant_id = 'default';
UPDATE roles SET description = 'Manages users, roles, groups and policies within the tenant'    WHERE name = 'tenant_admin'       AND tenant_id = 'default';
UPDATE roles SET description = 'Manages users and groups within an assigned domain'             WHERE name = 'domain_admin'       AND tenant_id = 'default';
UPDATE roles SET description = 'Reads and invokes wealth and servicing agents for their clients' WHERE name = 'relationship_manager' AND tenant_id = 'default';
UPDATE roles SET description = 'Drafts Cerbos policies for review'                              WHERE name = 'policy_author'      AND tenant_id = 'default';
UPDATE roles SET description = 'Approves and deploys policy drafts to production'               WHERE name = 'policy_approver'    AND tenant_id = 'default';
UPDATE roles SET description = 'Read-only access to audit logs for compliance review'           WHERE name = 'auditor'            AND tenant_id = 'default';
UPDATE roles SET description = 'Standard user — self-service profile access only'               WHERE name = 'member'             AND tenant_id = 'default';

-- ── Personas (password_hash = SEED_REPLACE_ME — DataSeeder updates at startup) ──
INSERT INTO principals (id, tenant_id, username, email, password_hash, is_active, attributes, created_at, updated_at)
VALUES
  -- Carlos Mendez — Senior RM, restricted clearance, handles premium + international clients
  ('rm_carlos', 'default', 'rm_carlos', 'carlos.mendez@meridian.demo', 'SEED_REPLACE_ME', TRUE,
   '{"classification":"restricted","segments":["wealth"],"admin_domains":[],"clearance":3,"display_name":"Carlos Mendez","title":"Senior Relationship Manager","department":"Wealth Management"}'::jsonb,
   NOW(), NOW()),

  -- Alex Park — Junior RM, internal clearance, limited client portfolio
  ('junior_rm', 'default', 'junior_rm', 'alex.park@meridian.demo', 'SEED_REPLACE_ME', TRUE,
   '{"classification":"internal","segments":["wealth"],"admin_domains":[],"clearance":1,"display_name":"Alex Park","title":"Junior Relationship Manager","department":"Wealth Advisory"}'::jsonb,
   NOW(), NOW()),

  -- Michael Torres — Domain Admin for Wealth, manages the wealth domain users/groups
  ('domain_admin_wealth', 'default', 'domain_admin_wealth', 'michael.torres@meridian.demo', 'SEED_REPLACE_ME', TRUE,
   '{"classification":"confidential","segments":["wealth"],"admin_domains":["wealth"],"clearance":2,"display_name":"Michael Torres","title":"Wealth Domain Administrator","department":"Wealth Management"}'::jsonb,
   NOW(), NOW()),

  -- Sarah Chen — Compliance Auditor, read-only on all audit logs
  ('auditor_sarah', 'default', 'auditor_sarah', 'sarah.chen@meridian.demo', 'SEED_REPLACE_ME', TRUE,
   '{"classification":"confidential","segments":[],"admin_domains":[],"clearance":2,"display_name":"Sarah Chen","title":"Compliance Auditor","department":"Legal & Compliance"}'::jsonb,
   NOW(), NOW()),

  -- Emma Watson — Policy Author, drafts Cerbos policies
  ('policy_author', 'default', 'policy_author', 'emma.watson@meridian.demo', 'SEED_REPLACE_ME', TRUE,
   '{"classification":"internal","segments":[],"admin_domains":[],"clearance":1,"display_name":"Emma Watson","title":"Security Policy Author","department":"Information Security"}'::jsonb,
   NOW(), NOW()),

  -- James Kim — Policy Approver / CISO, approves and deploys Emma's drafts
  ('policy_approver', 'default', 'policy_approver', 'james.kim@meridian.demo', 'SEED_REPLACE_ME', TRUE,
   '{"classification":"restricted","segments":[],"admin_domains":[],"clearance":3,"display_name":"James Kim","title":"Chief Information Security Officer","department":"Information Security"}'::jsonb,
   NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- Fix rm_jane display name while we're here
UPDATE principals
SET attributes = attributes || '{"display_name":"Jane Kowalski","title":"Relationship Manager","department":"Wealth Management"}'::jsonb
WHERE id = 'rm_jane';

UPDATE principals
SET attributes = attributes || '{"display_name":"System Administrator","title":"Platform Admin","department":"Technology"}'::jsonb
WHERE id = 'admin';

-- ── Role assignments ──────────────────────────────────────────────────────────
INSERT INTO principal_roles (principal_id, role_id, tenant_id)
SELECT 'rm_carlos', id, 'default' FROM roles WHERE name = 'relationship_manager' AND tenant_id = 'default'
ON CONFLICT DO NOTHING;

INSERT INTO principal_roles (principal_id, role_id, tenant_id)
SELECT 'junior_rm', id, 'default' FROM roles WHERE name = 'relationship_manager' AND tenant_id = 'default'
ON CONFLICT DO NOTHING;

INSERT INTO principal_roles (principal_id, role_id, tenant_id)
SELECT 'domain_admin_wealth', id, 'default' FROM roles WHERE name = 'domain_admin' AND tenant_id = 'default'
ON CONFLICT DO NOTHING;

INSERT INTO principal_roles (principal_id, role_id, tenant_id)
SELECT 'auditor_sarah', id, 'default' FROM roles WHERE name = 'auditor' AND tenant_id = 'default'
ON CONFLICT DO NOTHING;

INSERT INTO principal_roles (principal_id, role_id, tenant_id)
SELECT 'policy_author', id, 'default' FROM roles WHERE name = 'policy_author' AND tenant_id = 'default'
ON CONFLICT DO NOTHING;

INSERT INTO principal_roles (principal_id, role_id, tenant_id)
SELECT 'policy_approver', id, 'default' FROM roles WHERE name = 'policy_approver' AND tenant_id = 'default'
ON CONFLICT DO NOTHING;

-- ── Groups / Teams ────────────────────────────────────────────────────────────
INSERT INTO groups (id, tenant_id, name, domain_id, description, metadata, created_at)
VALUES
  (gen_random_uuid(), 'default', 'compliance',           'compliance', 'Compliance and audit team',
   '{"segments":[],"allowed_domains":["compliance"],"default_roles":["auditor"]}'::jsonb, NOW()),

  (gen_random_uuid(), 'default', 'wealth-domain-admins', 'wealth',     'Wealth domain administrators',
   '{"segments":["wealth"],"allowed_domains":["wealth"],"default_roles":["domain_admin"]}'::jsonb, NOW()),

  (gen_random_uuid(), 'default', 'information-security', 'platform',   'InfoSec — policy authors and approvers',
   '{"segments":[],"allowed_domains":["platform"],"default_roles":["policy_author","policy_approver"]}'::jsonb, NOW())
ON CONFLICT (tenant_id, name) DO NOTHING;

-- Add members to wealth-private-banking (already created in V1)
INSERT INTO group_members (group_id, principal_id)
SELECT g.id, p.id
FROM groups g CROSS JOIN principals p
WHERE g.name = 'wealth-private-banking' AND g.tenant_id = 'default'
  AND p.id IN ('rm_carlos', 'junior_rm', 'domain_admin_wealth')
ON CONFLICT DO NOTHING;

-- Add members to compliance group
INSERT INTO group_members (group_id, principal_id)
SELECT g.id, 'auditor_sarah'
FROM groups g WHERE g.name = 'compliance' AND g.tenant_id = 'default'
ON CONFLICT DO NOTHING;

-- Add members to wealth-domain-admins
INSERT INTO group_members (group_id, principal_id)
SELECT g.id, 'domain_admin_wealth'
FROM groups g WHERE g.name = 'wealth-domain-admins' AND g.tenant_id = 'default'
ON CONFLICT DO NOTHING;

-- Add members to information-security
INSERT INTO group_members (group_id, principal_id)
SELECT g.id, p.id
FROM groups g CROSS JOIN principals p
WHERE g.name = 'information-security' AND g.tenant_id = 'default'
  AND p.id IN ('policy_author', 'policy_approver')
ON CONFLICT DO NOTHING;

-- ── Personal Resources (relationship books) ───────────────────────────────────
-- rm_jane: Whitman + Calderon Trust
INSERT INTO personal_resources (id, tenant_id, principal_id, resource_type, resource_id, metadata, created_at)
VALUES
  (gen_random_uuid(), 'default', 'rm_jane', 'relationship', 'REL-00042',
   '{"client_name":"Jane Whitman","aum_usd":45000000,"tier":"ultra-hnw","since":"2019-03-15"}'::jsonb, NOW()),
  (gen_random_uuid(), 'default', 'rm_jane', 'relationship', 'REL-00099',
   '{"client_name":"Calderon Family Trust","aum_usd":12000000,"tier":"hnw","since":"2021-07-01"}'::jsonb, NOW())
ON CONFLICT (principal_id, resource_type, resource_id) DO NOTHING;

-- rm_carlos: Whitman (shared with Jane) + Okafor + Sterling Capital
INSERT INTO personal_resources (id, tenant_id, principal_id, resource_type, resource_id, metadata, created_at)
VALUES
  (gen_random_uuid(), 'default', 'rm_carlos', 'relationship', 'REL-00042',
   '{"client_name":"Jane Whitman","aum_usd":45000000,"tier":"ultra-hnw","since":"2017-11-20"}'::jsonb, NOW()),
  (gen_random_uuid(), 'default', 'rm_carlos', 'relationship', 'REL-00188',
   '{"client_name":"Okafor Family Account","aum_usd":8000000,"tier":"hnw","since":"2022-01-10"}'::jsonb, NOW()),
  (gen_random_uuid(), 'default', 'rm_carlos', 'relationship', 'REL-00201',
   '{"client_name":"Sterling Capital Partners","aum_usd":25000000,"tier":"ultra-hnw","since":"2020-05-14"}'::jsonb, NOW())
ON CONFLICT (principal_id, resource_type, resource_id) DO NOTHING;

-- junior_rm: single small client
INSERT INTO personal_resources (id, tenant_id, principal_id, resource_type, resource_id, metadata, created_at)
VALUES
  (gen_random_uuid(), 'default', 'junior_rm', 'relationship', 'REL-00314',
   '{"client_name":"Chen-Rodriguez Holdings","aum_usd":3000000,"tier":"standard","since":"2024-02-28"}'::jsonb, NOW())
ON CONFLICT (principal_id, resource_type, resource_id) DO NOTHING;

-- ── Policies — show lifecycle ─────────────────────────────────────────────────
INSERT INTO policies (id, tenant_id, name, resource_type, content, status, created_at, updated_at)
VALUES
  -- DEPLOYED: the live wealth agent policy
  (gen_random_uuid(), 'default', 'wealth-agent-policy', 'agent',
'apiVersion: api.cerbos.dev/v1
resourcePolicy:
  version: "default"
  resource: agent
  importDerivedRoles:
    - meridian_derived_roles
  rules:
    - actions: ["invoke"]
      effect: EFFECT_ALLOW
      roles: ["relationship_manager"]
      condition:
        match:
          all:
            of:
              - expr: "!R.attr.is_mutating"
              - expr: "R.attr.domain == \"wealth-management\" && P.attr.segments.exists(s, s == \"wealth\")"
',
   'deployed', NOW() - INTERVAL '14 days', NOW() - INTERVAL '7 days'),

  -- APPROVED: compliance auditor access policy, waiting for deployment
  (gen_random_uuid(), 'default', 'compliance-access-policy', 'audit_log',
'apiVersion: api.cerbos.dev/v1
resourcePolicy:
  version: "default"
  resource: iam-resource
  importDerivedRoles:
    - iam_derived_roles
  rules:
    - actions: ["read", "list", "export"]
      effect: EFFECT_ALLOW
      roles: ["auditor"]
      derivedRoles: ["same_tenant"]
      condition:
        match:
          expr: "R.attr.resource_type == \"audit_log\""
',
   'approved', NOW() - INTERVAL '5 days', NOW() - INTERVAL '2 days'),

  -- DRAFT: Emma is writing a restricted trading policy — not yet reviewed
  (gen_random_uuid(), 'default', 'restricted-trading-policy', 'agent',
'apiVersion: api.cerbos.dev/v1
# DRAFT - under review by James Kim
# Purpose: allow senior_trader role to invoke mutating (trading) agents
resourcePolicy:
  version: "default"
  resource: agent
  importDerivedRoles:
    - meridian_derived_roles
  rules:
    - actions: ["invoke"]
      effect: EFFECT_ALLOW
      roles: ["senior_trader"]
      condition:
        match:
          all:
            of:
              - expr: "R.attr.is_mutating == true"
              - expr: "R.attr.data_classification == \"restricted\""
              - expr: "int(P.attr.clearance) >= 3"
',
   'draft', NOW() - INTERVAL '1 day', NOW() - INTERVAL '1 day')
ON CONFLICT (tenant_id, name) DO NOTHING;

-- ── Pre-seeded Audit Log ──────────────────────────────────────────────────────
-- 12 entries that tell the bootstrap story — audit page non-empty from day one
INSERT INTO audit_log (id, tenant_id, actor_id, client_id, action, resource_type, resource_id, before_state, after_state, source_ip, occurred_at)
VALUES
  (gen_random_uuid(), 'default', 'admin', 'admin-ui', 'create', 'user', 'rm_jane',
   NULL,
   '{"username":"rm_jane","email":"jane.kowalski@meridian.demo","roles":[]}'::jsonb,
   '10.0.0.1', NOW() - INTERVAL '14 days'),

  (gen_random_uuid(), 'default', 'admin', 'admin-ui', 'create', 'user', 'rm_carlos',
   NULL,
   '{"username":"rm_carlos","email":"carlos.mendez@meridian.demo","roles":[]}'::jsonb,
   '10.0.0.1', NOW() - INTERVAL '14 days'),

  (gen_random_uuid(), 'default', 'admin', 'admin-ui', 'assign', 'role', 'rm_jane',
   '{"roles":[]}'::jsonb,
   '{"roles":["relationship_manager"]}'::jsonb,
   '10.0.0.1', NOW() - INTERVAL '13 days'),

  (gen_random_uuid(), 'default', 'admin', 'admin-ui', 'assign', 'role', 'rm_carlos',
   '{"roles":[]}'::jsonb,
   '{"roles":["relationship_manager"]}'::jsonb,
   '10.0.0.1', NOW() - INTERVAL '13 days'),

  (gen_random_uuid(), 'default', 'admin', 'admin-ui', 'create', 'relationship', 'REL-00042',
   NULL,
   '{"principal":"rm_jane","resource_type":"relationship","resource_id":"REL-00042","client_name":"Jane Whitman"}'::jsonb,
   '10.0.0.1', NOW() - INTERVAL '12 days'),

  (gen_random_uuid(), 'default', 'admin', 'admin-ui', 'create', 'relationship', 'REL-00099',
   NULL,
   '{"principal":"rm_jane","resource_type":"relationship","resource_id":"REL-00099","client_name":"Calderon Family Trust"}'::jsonb,
   '10.0.0.1', NOW() - INTERVAL '12 days'),

  (gen_random_uuid(), 'default', 'admin', 'admin-ui', 'create', 'relationship', 'REL-00188',
   NULL,
   '{"principal":"rm_carlos","resource_type":"relationship","resource_id":"REL-00188","client_name":"Okafor Family Account"}'::jsonb,
   '10.0.0.1', NOW() - INTERVAL '12 days'),

  (gen_random_uuid(), 'default', 'policy_author', 'admin-ui', 'create', 'policy', 'wealth-agent-policy',
   NULL,
   '{"name":"wealth-agent-policy","status":"draft","resource_type":"agent"}'::jsonb,
   '10.0.0.2', NOW() - INTERVAL '14 days'),

  (gen_random_uuid(), 'default', 'policy_approver', 'admin-ui', 'approve_policy', 'policy', 'wealth-agent-policy',
   '{"status":"draft"}'::jsonb,
   '{"status":"approved"}'::jsonb,
   '10.0.0.3', NOW() - INTERVAL '8 days'),

  (gen_random_uuid(), 'default', 'policy_approver', 'admin-ui', 'deploy_policy', 'policy', 'wealth-agent-policy',
   '{"status":"approved"}'::jsonb,
   '{"status":"deployed"}'::jsonb,
   '10.0.0.3', NOW() - INTERVAL '7 days'),

  (gen_random_uuid(), 'default', 'admin', 'admin-ui', 'create', 'user', 'auditor_sarah',
   NULL,
   '{"username":"auditor_sarah","roles":["auditor"]}'::jsonb,
   '10.0.0.1', NOW() - INTERVAL '5 days'),

  (gen_random_uuid(), 'default', 'admin', 'admin-ui', 'create', 'user', 'policy_author',
   NULL,
   '{"username":"policy_author","roles":["policy_author"]}'::jsonb,
   '10.0.0.1', NOW() - INTERVAL '3 days');
