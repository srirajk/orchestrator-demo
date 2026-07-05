-- ============================================================================
-- V8__seed_demo_bankers.sql
-- Additional demo bankers across business lines / segments, so Conduit Insights
-- shows real spread (cost & traffic by user + segment, denials, coverage gaps).
--
-- Same convention as V5/V7:
--   * runtime role = chat_user (front door to enterprise intelligence)
--   * segments is a per-segment classification MAP (internal < confidential < confidential-pii)
--   * password_hash = SEED_REPLACE_ME — DataSeeder rewrites to BCrypt(Meridian@2024) at startup
--   * no `book` — book-of-business is enforced by the coverage services at runtime; these new
--     users carry no personal_resources, so client-specific queries deny/clarify (real spread)
--     while HR/market-research/general questions answer (real cost per user + segment).
--
-- Idempotent: INSERT ... ON CONFLICT (id) DO UPDATE re-applies the segment map on re-run.
-- ============================================================================

INSERT INTO principals (id, tenant_id, username, email, password_hash, is_active, attributes, created_at, updated_at)
VALUES
  -- Kenji Nakamura — Private Bank RM (wealth, PII ceiling)
  ('rm_nakamura', 'default', 'rm_nakamura', 'kenji.nakamura@meridian.demo', 'SEED_REPLACE_ME', TRUE,
   '{"classification":"confidential-pii","segments":{"wealth":"confidential-pii"},"admin_domains":[],"display_name":"Kenji Nakamura","title":"Private Bank Relationship Manager","department":"Wealth Management"}'::jsonb,
   NOW(), NOW()),

  -- Adaeze Okoro — Commercial Banker (servicing)
  ('comm_banker_okoro', 'default', 'comm_banker_okoro', 'adaeze.okoro@meridian.demo', 'SEED_REPLACE_ME', TRUE,
   '{"classification":"confidential","segments":{"servicing":"confidential"},"admin_domains":[],"display_name":"Adaeze Okoro","title":"Commercial Banker","department":"Commercial Banking"}'::jsonb,
   NOW(), NOW()),

  -- Luca Bianchi — Wealth Advisor (wealth, confidential — research yes, PII holdings no)
  ('wealth_adv_bianchi', 'default', 'wealth_adv_bianchi', 'luca.bianchi@meridian.demo', 'SEED_REPLACE_ME', TRUE,
   '{"classification":"confidential","segments":{"wealth":"confidential"},"admin_domains":[],"display_name":"Luca Bianchi","title":"Wealth Advisor","department":"Wealth Advisory"}'::jsonb,
   NOW(), NOW()),

  -- Priya Singh — Operations Analyst (asset servicing, PII ceiling)
  ('ops_analyst_singh', 'default', 'ops_analyst_singh', 'priya.singh@meridian.demo', 'SEED_REPLACE_ME', TRUE,
   '{"classification":"confidential-pii","segments":{"servicing":"confidential-pii"},"admin_domains":[],"display_name":"Priya Singh","title":"Operations Analyst","department":"Asset Servicing"}'::jsonb,
   NOW(), NOW()),

  -- Marco Costa — Insurance Underwriter (insurance, PII ceiling)
  ('ins_uw_costa', 'default', 'ins_uw_costa', 'marco.costa@meridian.demo', 'SEED_REPLACE_ME', TRUE,
   '{"classification":"confidential-pii","segments":{"insurance":"confidential-pii"},"admin_domains":[],"display_name":"Marco Costa","title":"Insurance Underwriter","department":"Insurance Underwriting"}'::jsonb,
   NOW(), NOW()),

  -- Astrid Lund — HR Business Partner (hr)
  ('hr_partner_lund', 'default', 'hr_partner_lund', 'astrid.lund@meridian.demo', 'SEED_REPLACE_ME', TRUE,
   '{"classification":"confidential","segments":{"hr":"confidential"},"admin_domains":[],"display_name":"Astrid Lund","title":"HR Business Partner","department":"Human Resources"}'::jsonb,
   NOW(), NOW()),

  -- Camille Moreau — Treasury Operations (servicing)
  ('treasury_moreau', 'default', 'treasury_moreau', 'camille.moreau@meridian.demo', 'SEED_REPLACE_ME', TRUE,
   '{"classification":"confidential","segments":{"servicing":"confidential"},"admin_domains":[],"display_name":"Camille Moreau","title":"Treasury Operations Specialist","department":"Treasury"}'::jsonb,
   NOW(), NOW()),

  -- Hans Fischer — Multi-line RM (wealth PII + insurance) — cross-segment cost spread
  ('multi_rm_fischer', 'default', 'multi_rm_fischer', 'hans.fischer@meridian.demo', 'SEED_REPLACE_ME', TRUE,
   '{"classification":"confidential-pii","segments":{"wealth":"confidential-pii","insurance":"confidential"},"admin_domains":[],"display_name":"Hans Fischer","title":"Multi-line Relationship Manager","department":"Private Banking"}'::jsonb,
   NOW(), NOW())
ON CONFLICT (id) DO UPDATE
  SET attributes = (principals.attributes - 'clearance') || EXCLUDED.attributes,
      is_active  = TRUE,
      updated_at = NOW();

-- Runtime role: chat_user for every new banker.
INSERT INTO principal_roles (principal_id, role_id, tenant_id)
SELECT p.id, r.id, 'default'
FROM (VALUES
        ('rm_nakamura'), ('comm_banker_okoro'), ('wealth_adv_bianchi'), ('ops_analyst_singh'),
        ('ins_uw_costa'), ('hr_partner_lund'), ('treasury_moreau'), ('multi_rm_fischer')
     ) AS p(id)
CROSS JOIN roles r
WHERE r.name = 'chat_user' AND r.tenant_id = 'default'
ON CONFLICT DO NOTHING;
