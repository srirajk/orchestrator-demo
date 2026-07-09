-- Give the asset-servicing operations analyst a concrete relationship for live
-- servicing demos that still pass the relationship coverage pre-check.
INSERT INTO personal_resources (id, tenant_id, principal_id, resource_type, resource_id, metadata, created_at)
VALUES (
  gen_random_uuid(),
  'default',
  'ops_analyst_singh',
  'relationship',
  'REL-00188',
  '{"name":"Okafor Family Account","segment":"servicing","purpose":"settlement-risk-demo"}'::jsonb,
  NOW()
)
ON CONFLICT DO NOTHING;
