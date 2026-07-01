-- ============================================================================
-- V3__seed_e2e_users.sql
-- E2E test personas: rm_diaz (dual-segment RM) and da_wpb (wealth-private-banking domain admin)
-- password_hash = SEED_REPLACE_ME — DataSeeder replaces with BCrypt of IAM_ADMIN_PASSWORD at startup
-- ============================================================================

INSERT INTO principals (id, tenant_id, username, email, password_hash, is_active, attributes, created_at, updated_at)
VALUES
  -- rm_diaz: Relationship Manager with dual segments (wealth + servicing), empty book
  ('rm_diaz', 'default', 'rm_diaz', 'diaz@meridian.demo', 'SEED_REPLACE_ME', TRUE,
   '{"classification":"confidential","segments":["wealth","servicing"],"admin_domains":[],"clearance":2,"display_name":"Maria Diaz","title":"Relationship Manager","department":"Wealth & Servicing"}'::jsonb,
   NOW(), NOW()),

  -- da_wpb: Domain Admin scoped to wealth-private-banking
  ('da_wpb', 'default', 'da_wpb', 'da_wpb@meridian.demo', 'SEED_REPLACE_ME', TRUE,
   '{"classification":"confidential","segments":["wealth"],"admin_domains":["wealth-private-banking"],"clearance":2,"display_name":"WPB Domain Admin","title":"Domain Administrator","department":"Wealth Management"}'::jsonb,
   NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- Role assignments
INSERT INTO principal_roles (principal_id, role_id, tenant_id)
SELECT 'rm_diaz', id, 'default' FROM roles WHERE name = 'relationship_manager' AND tenant_id = 'default'
ON CONFLICT DO NOTHING;

INSERT INTO principal_roles (principal_id, role_id, tenant_id)
SELECT 'da_wpb', id, 'default' FROM roles WHERE name = 'domain_admin' AND tenant_id = 'default'
ON CONFLICT DO NOTHING;
