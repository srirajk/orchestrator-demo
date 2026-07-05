-- ============================================================================
-- V7__seed_conduit_admin.sql
-- Conduit Insights admin gate (ADDITIVE ONLY — no existing role/user/decision changes).
--   * New role  `conduit_admin`  — least-privilege access to the native analytics API
--                                  (permission `insights:read`); NOT platform_admin.
--   * New user  `insights_admin` — carries only conduit_admin, to prove the Insights gate
--                                  works off the dedicated role (not platform superuser).
-- password_hash = SEED_REPLACE_ME — DataSeeder replaces with BCrypt of IAM_ADMIN_PASSWORD at startup
-- (same convention as V1/V3), so this user logs in with the standard demo password.
-- ============================================================================

-- New role: conduit_admin (analytics read-only)
INSERT INTO roles (id, tenant_id, name, permissions, description)
VALUES ('c0000000-0000-0000-0000-0000000000c1'::uuid, 'default', 'conduit_admin',
        '["insights:read"]'::jsonb,
        'Conduit Insights administrator — read-only access to the native analytics boards')
ON CONFLICT (tenant_id, name) DO NOTHING;

-- New user: insights_admin (conduit_admin only)
INSERT INTO principals (id, tenant_id, username, email, password_hash, is_active, attributes, created_at, updated_at)
VALUES
  ('insights_admin', 'default', 'insights_admin', 'insights_admin@meridian.demo', 'SEED_REPLACE_ME', TRUE,
   '{"classification":"internal","segments":[],"admin_domains":[],"display_name":"Insights Admin","title":"Analytics Administrator","department":"Platform"}'::jsonb,
   NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- Assign conduit_admin to insights_admin
INSERT INTO principal_roles (principal_id, role_id, tenant_id)
SELECT 'insights_admin', id, 'default' FROM roles WHERE name = 'conduit_admin' AND tenant_id = 'default'
ON CONFLICT DO NOTHING;
