-- ============================================================================
-- V4__reconcile_personas.sql
-- Reconcile the four demo personas so Axiom (OIDC) is the SINGLE source of truth
-- for identity. This closes the persona seed/auth DRIFT documented in
-- TEST-KIT-FINDINGS.md (bugs 234/235) where rm_guest + uw_sam only existed in the
-- legacy Redis X-User-Id seed and rm_jane had lost her `servicing` segment.
--
-- This is DATA correctness only — no Axiom auth-logic changes. Identity is generic:
-- roles, segments (business lines), domain membership (group), clearance. There is
-- NO "book" on the principal — per-domain coverage services own principal→entities.
--
-- Intended matrix (see TEST-KIT.md §B/§C):
--   rm_jane   segments [wealth, servicing]  domain wealth-private-banking   RM
--   rm_carlos segments [wealth]             domain wealth-private-banking   RM
--   rm_guest  segments [wealth]             (no domain membership)          RM
--   uw_sam    segments [insurance]          domain insurance-underwriting   RM
--
-- Password: new personas seed the SEED_REPLACE_ME placeholder, which DataSeeder
-- rewrites to BCrypt(Meridian@2024) at iam-service startup (same as V1/V2/V3).
-- Idempotent: INSERT ... ON CONFLICT DO UPDATE; the password_hash is never reset
-- on conflict so an already-hashed password survives re-runs.
-- ============================================================================

-- ── 235: rm_jane regains her `servicing` segment (drifted to [wealth] only) ───
-- Merge so we only touch `segments`; display_name/title/clearance stay intact.
UPDATE principals
SET attributes = attributes || '{"segments":["wealth","servicing"]}'::jsonb,
    updated_at = NOW()
WHERE id = 'rm_jane';

-- ── rm_carlos: verify/normalize — already seeded in V2 as wealth-only RM ───────
-- Defensive UPDATE guarantees the intended segment set regardless of prior drift.
UPDATE principals
SET attributes = attributes || '{"segments":["wealth"]}'::jsonb,
    updated_at = NOW()
WHERE id = 'rm_carlos';

-- ── 234: rm_guest — login-capable, wealth segment, NO domain membership ───────
-- Proves domain/coverage denial: structurally in the wealth business line, but
-- with no coverage book the per-domain coverage service denies every entity.
INSERT INTO principals (id, tenant_id, username, email, password_hash, is_active, attributes, created_at, updated_at)
VALUES
  ('rm_guest', 'default', 'rm_guest', 'guest.rm@meridian.demo', 'SEED_REPLACE_ME', TRUE,
   '{"classification":"confidential","segments":["wealth"],"admin_domains":[],"clearance":2,"display_name":"Guest RM","title":"Relationship Manager (Unassigned)","department":"Wealth Management"}'::jsonb,
   NOW(), NOW())
ON CONFLICT (id) DO UPDATE
  SET attributes = principals.attributes
        || '{"segments":["wealth"],"admin_domains":[],"clearance":2}'::jsonb,
      is_active  = TRUE,
      updated_at = NOW();

-- ── 234: uw_sam — login-capable, insurance segment, insurance-underwriting ────
-- Second domain (insurance). Role relationship_manager because the deployed Cerbos
-- agent policy grants `invoke` to relationship_manager gated by segment membership
-- (there is no separate underwriter invoke rule); segment `insurance` maps to the
-- insurance agent domain. Clearance 2 satisfies the confidential-pii tier gate.
INSERT INTO principals (id, tenant_id, username, email, password_hash, is_active, attributes, created_at, updated_at)
VALUES
  ('uw_sam', 'default', 'uw_sam', 'sam.underwriter@meridian.demo', 'SEED_REPLACE_ME', TRUE,
   '{"classification":"confidential","segments":["insurance"],"admin_domains":[],"clearance":2,"display_name":"Sam Underwriter","title":"Underwriter","department":"Insurance Underwriting"}'::jsonb,
   NOW(), NOW())
ON CONFLICT (id) DO UPDATE
  SET attributes = principals.attributes
        || '{"segments":["insurance"],"admin_domains":[],"clearance":2}'::jsonb,
      is_active  = TRUE,
      updated_at = NOW();

-- ── Role assignments (relationship_manager for both new personas) ─────────────
INSERT INTO principal_roles (principal_id, role_id, tenant_id)
SELECT 'rm_guest', id, 'default' FROM roles WHERE name = 'relationship_manager' AND tenant_id = 'default'
ON CONFLICT DO NOTHING;

INSERT INTO principal_roles (principal_id, role_id, tenant_id)
SELECT 'uw_sam', id, 'default' FROM roles WHERE name = 'relationship_manager' AND tenant_id = 'default'
ON CONFLICT DO NOTHING;

-- ── Domain membership: uw_sam → insurance-underwriting group ──────────────────
-- rm_jane/rm_carlos already belong to wealth-private-banking (V1/V2). rm_guest is
-- intentionally left out of every group (no domain membership). Create the
-- insurance-underwriting org-domain group if absent, then add uw_sam.
INSERT INTO groups (id, tenant_id, name, domain_id, description, metadata, created_at)
VALUES
  (gen_random_uuid(), 'default', 'insurance-underwriting', 'insurance', 'Insurance Underwriting domain team',
   '{"segments":["insurance"],"allowedDomains":["insurance"],"defaultRoles":["relationship_manager"]}'::jsonb, NOW())
ON CONFLICT (tenant_id, name) DO NOTHING;

INSERT INTO group_members (group_id, principal_id)
SELECT g.id, 'uw_sam'
FROM groups g WHERE g.name = 'insurance-underwriting' AND g.tenant_id = 'default'
ON CONFLICT DO NOTHING;
