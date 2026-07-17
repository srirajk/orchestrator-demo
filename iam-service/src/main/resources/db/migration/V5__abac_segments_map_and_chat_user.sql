-- ============================================================================
-- V5__abac_segments_map_and_chat_user.sql
-- ABAC model migration (see AUTHZ-SPEC.md). CLAIM SHAPE / SEED DATA ONLY — no
-- Axiom auth-logic changes.
--
--   1. `segments` attribute: flat array  ->  per-segment classification MAP
--        {segment -> the data tier the principal holds IN that segment}.
--   2. Numeric `clearance` attribute: DROPPED (the per-segment tier is the ceiling).
--   3. Runtime role: everyone on the chat front door is `chat_user`
--        (relationship_manager is retired for these personas; the Cerbos policy keeps
--         it as an alias, so this is purely a claim-shape cleanup).
--   4. New demo user `analyst_amy` — wealth @ confidential (exercises the classification
--        gate: research/internal ✓, holdings/confidential-pii ✗).
--
-- Per-segment tiers (AUTHZ-SPEC §3.4 + Step-0 mapping — chosen to PRESERVE each existing
-- persona's Step-0 allow/deny exactly):
--   rm_jane   {wealth: confidential-pii, servicing: confidential}
--   rm_carlos {wealth: confidential-pii}
--   uw_sam    {insurance: confidential-pii}
--   rm_guest  {wealth: confidential-pii}
--   analyst_amy {wealth: confidential}   (NEW)
--
-- Idempotent: UPDATE ... jsonb merge; INSERT ... ON CONFLICT DO UPDATE. Passwords use the
-- SEED_REPLACE_ME placeholder that DataSeeder rewrites to BCrypt(Meridian@2024) at startup.
-- ============================================================================

-- ── 1. Runtime role: chat_user ───────────────────────────────────────────────
INSERT INTO roles (id, tenant_id, name, permissions, description) VALUES
  (gen_random_uuid(), 'default', 'chat_user',
   '["relationships:read","holdings:read","risk:read"]'::jsonb,
   'Runtime chat user — front door to enterprise intelligence (ABAC over segments)')
ON CONFLICT (tenant_id, name) DO NOTHING;

-- ── 2. Per-segment maps + drop clearance for the four existing personas ───────
--     (attributes - 'clearance') removes the numeric ladder; the || merge REPLACES the
--     top-level `segments` key (old array) with the new object.
UPDATE principals
SET attributes = (attributes - 'clearance')
      || '{"segments":{"wealth":"confidential-pii","servicing":"confidential"}}'::jsonb,
    updated_at = NOW()
WHERE id = 'rm_jane';

UPDATE principals
SET attributes = (attributes - 'clearance')
      || '{"segments":{"wealth":"confidential-pii"}}'::jsonb,
    updated_at = NOW()
WHERE id = 'rm_carlos';

UPDATE principals
SET attributes = (attributes - 'clearance')
      || '{"segments":{"insurance":"confidential-pii"}}'::jsonb,
    updated_at = NOW()
WHERE id = 'uw_sam';

UPDATE principals
SET attributes = (attributes - 'clearance')
      || '{"segments":{"wealth":"confidential-pii"}}'::jsonb,
    updated_at = NOW()
WHERE id = 'rm_guest';

-- ── 3. Retire relationship_manager for these personas, grant chat_user ────────
INSERT INTO principal_roles (principal_id, role_id, tenant_id)
SELECT p.id, r.id, 'default'
FROM (VALUES ('rm_jane'), ('rm_carlos'), ('uw_sam'), ('rm_guest')) AS p(id)
CROSS JOIN roles r
WHERE r.name = 'chat_user' AND r.tenant_id = 'default'
ON CONFLICT DO NOTHING;

DELETE FROM principal_roles
WHERE principal_id IN ('rm_jane', 'rm_carlos', 'uw_sam', 'rm_guest')
  AND role_id IN (SELECT id FROM roles WHERE name = 'relationship_manager' AND tenant_id = 'default');

-- ── 4. New demo user analyst_amy — wealth @ confidential, chat_user ───────────
INSERT INTO principals (id, tenant_id, username, email, password_hash, is_active, attributes, created_at, updated_at)
VALUES
  ('analyst_amy', 'default', 'analyst_amy', 'amy.analyst@meridian.demo', 'SEED_REPLACE_ME', TRUE,
   '{"classification":"confidential","segments":{"wealth":"confidential"},"admin_domains":[],"display_name":"Amy Analyst","title":"Investment Analyst","department":"Wealth Management"}'::jsonb,
   NOW(), NOW())
ON CONFLICT (id) DO UPDATE
  SET attributes = (principals.attributes - 'clearance')
        || '{"segments":{"wealth":"confidential"},"admin_domains":[]}'::jsonb,
      is_active  = TRUE,
      updated_at = NOW();

INSERT INTO principal_roles (principal_id, role_id, tenant_id)
SELECT 'analyst_amy', id, 'default' FROM roles WHERE name = 'chat_user' AND tenant_id = 'default'
ON CONFLICT DO NOTHING;
