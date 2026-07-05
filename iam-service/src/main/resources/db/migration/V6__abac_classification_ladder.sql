-- ============================================================================
-- V6__abac_classification_ladder.sql
-- Aligns the tenant's data-classification schema with the ABAC model (AUTHZ-SPEC §1/§2):
-- the per-segment clearance ladder is  internal < confidential < confidential-pii.
--
-- This is the LIVE source the Axiom admin console reads for the per-segment "tier" dropdown
-- on the Users screen (`GET /tenants/default/classification-schema`). Before V6 the schema
-- listed public / internal / confidential / restricted — it lacked `confidential-pii` (so the
-- ABAC ceiling was not selectable) and carried the phantom `restricted` tier the spec removes.
--
-- CLAIM SHAPE / REFERENCE DATA ONLY. No principal attributes change here, so every existing
-- persona's token (roles + per-segment map) is byte-identical after this migration. No Axiom
-- auth-decision logic is touched.
--
-- Idempotent: a straight UPDATE of the tenant row.
-- ============================================================================

UPDATE tenants
SET classification_schema = '[
  {"name": "internal",         "rank": 1, "description": "Internal staff — general, non-sensitive business data"},
  {"name": "confidential",     "rank": 2, "description": "Confidential — relationship managers and above"},
  {"name": "confidential-pii", "rank": 3, "description": "Confidential + PII — senior staff; personal and trading data"}
]'::jsonb
WHERE id = 'default';
