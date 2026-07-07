-- langfuse-dashboard.sql — Idempotently seed the "Conduit — LLM Quality & Cost" dashboard.
--
-- The Langfuse public API can CREATE widgets (POST /api/public/unstable/dashboard-widgets)
-- but cannot PLACE them on a dashboard grid (layout is UI-only). So we write both the
-- widgets and the dashboard row (with its grid `definition`) directly into Langfuse Postgres.
--
-- Every widget shape here was first round-tripped through the public widget-create API so it
-- is API-validated; the one exception is the Trace-volume widget, whose `traces` view the
-- *unstable* widget API rejects (traces view is not supported there) — it is written directly.
--
-- Self-contained: project_id and owner are resolved by sub-query, so no psql variables are
-- required. Fully idempotent: the widgets + dashboard are deleted by their stable ids first,
-- so re-running (or a `down -v` → up → seed) recreates an identical dashboard.
--
-- Data it visualises (all present in ClickHouse behind this Langfuse project):
--   observations (cost, latency, tokens, model name), traces (volume),
--   scores-numeric (grounding / relevance / safety / partial_honesty).

BEGIN;

-- ── Resolve project + owner once ─────────────────────────────────────────────
-- First (oldest) project; the Conduit stack self-seeds exactly one.
CREATE TEMP TABLE _ctx ON COMMIT DROP AS
SELECT
  (SELECT id FROM projects ORDER BY created_at LIMIT 1)                         AS project_id,
  (SELECT id FROM users ORDER BY created_at LIMIT 1)                            AS owner_id;

-- ── Idempotency: drop prior copies by stable id ──────────────────────────────
DELETE FROM dashboards        WHERE id = 'conduit-dash-llm-quality-cost';
DELETE FROM dashboard_widgets WHERE id IN (
  'conduit-w-cost','conduit-w-scores','conduit-w-traces',
  'conduit-w-latency','conduit-w-tokens','conduit-w-scorehist'
);

-- ── Widgets ──────────────────────────────────────────────────────────────────
-- 1) Cost over time — by model  (total cost, split by model, generations only)
INSERT INTO dashboard_widgets
  (id, created_at, updated_at, created_by, updated_by, project_id, name, description,
   view, dimensions, metrics, filters, chart_type, chart_config, min_version)
SELECT 'conduit-w-cost', now(), now(), owner_id, owner_id, project_id,
       'Cost over time — by model',
       'Total generation cost (USD) over time, split by model name.',
       'OBSERVATIONS',
       '[{"field":"providedModelName"}]'::jsonb,
       '[{"agg":"sum","measure":"totalCost"}]'::jsonb,
       '[{"type":"string","value":"GENERATION","column":"type","operator":"="}]'::jsonb,
       'LINE_TIME_SERIES', '{"type":"LINE_TIME_SERIES"}'::jsonb, 1
FROM _ctx;

-- 2) Eval scores over time  (avg numeric score, split by score name)
INSERT INTO dashboard_widgets
  (id, created_at, updated_at, created_by, updated_by, project_id, name, description,
   view, dimensions, metrics, filters, chart_type, chart_config, min_version)
SELECT 'conduit-w-scores', now(), now(), owner_id, owner_id, project_id,
       'Eval scores over time',
       'Average LLM-judge score over time, split by evaluator (grounding / relevance / safety / partial_honesty).',
       'SCORES_NUMERIC',
       '[{"field":"name"}]'::jsonb,
       '[{"agg":"avg","measure":"value"}]'::jsonb,
       '[]'::jsonb,
       'LINE_TIME_SERIES', '{"type":"LINE_TIME_SERIES"}'::jsonb, 1
FROM _ctx;

-- 3) Trace volume over time  (count of traces)  — traces view, written directly
INSERT INTO dashboard_widgets
  (id, created_at, updated_at, created_by, updated_by, project_id, name, description,
   view, dimensions, metrics, filters, chart_type, chart_config, min_version)
SELECT 'conduit-w-traces', now(), now(), owner_id, owner_id, project_id,
       'Trace volume over time',
       'Number of traces (chat requests) over time.',
       'TRACES',
       '[]'::jsonb,
       '[{"agg":"count","measure":"count"}]'::jsonb,
       '[]'::jsonb,
       'BAR_TIME_SERIES', '{"type":"BAR_TIME_SERIES"}'::jsonb, 1
FROM _ctx;

-- 4) Generation latency  (p50 / p95, generations only)
INSERT INTO dashboard_widgets
  (id, created_at, updated_at, created_by, updated_by, project_id, name, description,
   view, dimensions, metrics, filters, chart_type, chart_config, min_version)
SELECT 'conduit-w-latency', now(), now(), owner_id, owner_id, project_id,
       'Generation latency (p50 / p95)',
       'Generation latency percentiles (seconds) over time.',
       'OBSERVATIONS',
       '[]'::jsonb,
       '[{"agg":"p50","measure":"latency"},{"agg":"p95","measure":"latency"}]'::jsonb,
       '[{"type":"string","value":"GENERATION","column":"type","operator":"="}]'::jsonb,
       'LINE_TIME_SERIES', '{"type":"LINE_TIME_SERIES"}'::jsonb, 1
FROM _ctx;

-- 5) Token usage over time  (input + output tokens)
INSERT INTO dashboard_widgets
  (id, created_at, updated_at, created_by, updated_by, project_id, name, description,
   view, dimensions, metrics, filters, chart_type, chart_config, min_version)
SELECT 'conduit-w-tokens', now(), now(), owner_id, owner_id, project_id,
       'Token usage over time',
       'Input and output token totals over time.',
       'OBSERVATIONS',
       '[]'::jsonb,
       '[{"agg":"sum","measure":"inputTokens"},{"agg":"sum","measure":"outputTokens"}]'::jsonb,
       '[]'::jsonb,
       'AREA_TIME_SERIES', '{"type":"AREA_TIME_SERIES"}'::jsonb, 1
FROM _ctx;

-- 6) Grounding score distribution  (histogram of the grounding / faithfulness eval)
--    Scoped to ONE evaluator on purpose: pooling four semantically different judges
--    (grounding / relevance / safety / partial_honesty) into a single histogram mixes
--    incommensurable scales and hides which judge is weak. Grounding is the flagship
--    faithfulness / anti-hallucination guard for the gateway, so its shape (how tightly
--    answers cluster near 1.0, and the low-score tail) is the trust signal worth its own
--    widget. The per-evaluator AVERAGES over time are already the "Eval scores" line.
INSERT INTO dashboard_widgets
  (id, created_at, updated_at, created_by, updated_by, project_id, name, description,
   view, dimensions, metrics, filters, chart_type, chart_config, min_version)
SELECT 'conduit-w-scorehist', now(), now(), owner_id, owner_id, project_id,
       'Grounding score distribution',
       'Distribution of the grounding (faithfulness) eval score, 0–1 — how tightly answers stay grounded in agent data. Higher and tighter-to-1 is better; the left tail is ungrounded answers.',
       'SCORES_NUMERIC',
       '[]'::jsonb,
       '[{"agg":"histogram","measure":"value"}]'::jsonb,
       '[{"type":"string","value":"grounding","column":"name","operator":"="}]'::jsonb,
       'HISTOGRAM', '{"type":"HISTOGRAM","bins":10}'::jsonb, 1
FROM _ctx;

-- ── Dashboard row (grid layout in `definition`) ──────────────────────────────
-- 12-column grid; each widget 6 wide × 6 tall, arranged two-per-row over three rows.
INSERT INTO dashboards
  (id, created_at, updated_at, created_by, updated_by, project_id, name, description,
   definition, filters)
SELECT 'conduit-dash-llm-quality-cost', now(), now(), owner_id, owner_id, project_id,
       'Conduit — LLM Quality & Cost',
       'Grounded-answer quality and spend for the Conduit AI gateway: cost by model, eval scores, trace volume, latency, token usage, and score distribution.',
       jsonb_build_object('widgets', jsonb_build_array(
         jsonb_build_object('type','widget','id','conduit-p-cost',     'widgetId','conduit-w-cost',     'x',0,'y',0, 'x_size',6,'y_size',6),
         jsonb_build_object('type','widget','id','conduit-p-scores',   'widgetId','conduit-w-scores',   'x',6,'y',0, 'x_size',6,'y_size',6),
         jsonb_build_object('type','widget','id','conduit-p-traces',   'widgetId','conduit-w-traces',   'x',0,'y',6, 'x_size',6,'y_size',6),
         jsonb_build_object('type','widget','id','conduit-p-latency',  'widgetId','conduit-w-latency',  'x',6,'y',6, 'x_size',6,'y_size',6),
         jsonb_build_object('type','widget','id','conduit-p-tokens',   'widgetId','conduit-w-tokens',   'x',0,'y',12,'x_size',6,'y_size',6),
         jsonb_build_object('type','widget','id','conduit-p-scorehist','widgetId','conduit-w-scorehist','x',6,'y',12,'x_size',6,'y_size',6)
       )),
       '[]'::jsonb
FROM _ctx;

COMMIT;

\echo 'Seeded dashboard: Conduit — LLM Quality & Cost (6 widgets).'
