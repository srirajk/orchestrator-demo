#!/usr/bin/env node
/**
 * F5 latency harness (spec §3d) — network-level percentile sweep against a running (stub) gateway.
 *
 * Sweeps a target concurrency set and, per step, emits
 *   { git_sha, path_kind, concurrency, n, p50, p95, p99, max, error_rate, statuses, error_taxonomy }
 * where error_taxonomy buckets { timeout, connection_reset, http_5xx, http_4xx, sse_truncated }.
 *
 * It also extracts each response's SSE `id` and writes the NORMALIZED transaction-id list to ids.json
 * (strip the `chatcmpl-` prefix, lowercase). Reconciliation (scripts/audit-verify.py reconcile) then
 * compares dash-stripped lowercase hex on both sides — total for both the OTel trace-id form and the
 * UUID fallback (fixed 8-4-4-4-12 dash positions make the strip lossless).
 *
 * The in-JVM counterpart (LatencyHarnessTest) proves the percentile logic under Testcontainers; this
 * script is the live-stack artifact. Run against the stub compose overlay (docker-compose.stub.yml).
 *
 * Usage: node latency-harness.mjs --concurrency 1,5,10,25 --out sweep.json --ids ids.json
 */
import { writeFileSync } from 'node:fs';

const args = parseArgs(process.argv.slice(2));
const GATEWAY_URL = process.env.GATEWAY_URL || 'http://localhost:8080';
const AUTH_TOKEN = process.env.AUTH_TOKEN || '';
const PATH_KIND = args['path-kind'] || process.env.PATH_KIND || 'flat';
const CONCURRENCY = (args.concurrency || '1,5,10,25').split(',').map((s) => parseInt(s, 10));
const REQUESTS_PER_WORKER = parseInt(args.requests || process.env.REQUESTS || '10', 10);
const TIMEOUT_MS = parseInt(process.env.TIMEOUT_MS || '30000', 10);
const OUT = args.out || 'sweep.json';
const IDS_OUT = args.ids || 'ids.json';
const GIT_SHA = process.env.GIT_SHA || 'unknown';

const PROMPTS = [
  'Give me a short status summary.',
  'What is the current overview?',
  'Summarize the latest position.',
];

function parseArgs(argv) {
  const out = {};
  for (let i = 0; i < argv.length; i++) {
    if (argv[i].startsWith('--')) {
      const key = argv[i].slice(2);
      out[key] = argv[i + 1] && !argv[i + 1].startsWith('--') ? argv[++i] : 'true';
    }
  }
  return out;
}

function percentile(sortedAsc, q) {
  if (sortedAsc.length === 0) return 0;
  const rank = Math.ceil(q * sortedAsc.length);
  return sortedAsc[Math.min(Math.max(rank - 1, 0), sortedAsc.length - 1)];
}

/** Normalize an SSE id to dash-stripped lowercase hex (drop the chatcmpl- prefix). */
function normalizeId(id) {
  if (!id) return null;
  return id.replace(/^chatcmpl-/, '').replace(/-/g, '').toLowerCase();
}

async function oneRequest(prompt, taxonomy, ids) {
  const started = Date.now();
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), TIMEOUT_MS);
  try {
    const res = await fetch(`${GATEWAY_URL}/v1/chat/completions`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...(AUTH_TOKEN ? { Authorization: `Bearer ${AUTH_TOKEN}` } : {}),
      },
      body: JSON.stringify({ model: 'conduit', stream: true, messages: [{ role: 'user', content: prompt }] }),
      signal: ctrl.signal,
    });
    if (res.status >= 500) { taxonomy.http_5xx++; return { latency: Date.now() - started, status: 'HTTP_5XX' }; }
    if (res.status >= 400) { taxonomy.http_4xx++; return { latency: Date.now() - started, status: 'HTTP_4XX' }; }

    const text = await res.text();
    // Extract the SSE id (first data frame carrying an "id" field).
    const m = text.match(/"id"\s*:\s*"([^"]+)"/);
    if (m) ids.push(normalizeId(m[1]));
    // A well-formed OpenAI stream ends with [DONE]; absence means a truncated stream.
    if (!text.includes('[DONE]')) { taxonomy.sse_truncated++; return { latency: Date.now() - started, status: 'SSE_TRUNCATED' }; }
    return { latency: Date.now() - started, status: 'OK' };
  } catch (e) {
    if (e.name === 'AbortError') { taxonomy.timeout++; return { latency: Date.now() - started, status: 'TIMEOUT' }; }
    if (String(e).includes('ECONNRESET') || String(e).includes('other side closed')) {
      taxonomy.connection_reset++; return { latency: Date.now() - started, status: 'CONNECTION_RESET' };
    }
    taxonomy.connection_reset++;
    return { latency: Date.now() - started, status: 'ERROR' };
  } finally {
    clearTimeout(timer);
  }
}

async function runStep(concurrency, ids) {
  const taxonomy = { timeout: 0, connection_reset: 0, http_5xx: 0, http_4xx: 0, sse_truncated: 0 };
  const statuses = {};
  const latencies = [];

  const worker = async (w) => {
    for (let i = 0; i < REQUESTS_PER_WORKER; i++) {
      const prompt = PROMPTS[(w + i) % PROMPTS.length];
      const r = await oneRequest(prompt, taxonomy, ids);
      latencies.push(r.latency);
      statuses[r.status] = (statuses[r.status] || 0) + 1;
    }
  };
  await Promise.all(Array.from({ length: concurrency }, (_, w) => worker(w)));

  latencies.sort((a, b) => a - b);
  const n = latencies.length;
  const errors = n - (statuses.OK || 0);
  return {
    git_sha: GIT_SHA,
    path_kind: PATH_KIND,
    concurrency,
    n,
    p50: percentile(latencies, 0.5),
    p95: percentile(latencies, 0.95),
    p99: percentile(latencies, 0.99),
    max: n ? latencies[n - 1] : 0,
    error_rate: n ? errors / n : 0,
    statuses,
    error_taxonomy: taxonomy,
  };
}

(async () => {
  const sweep = [];
  const ids = [];
  for (const c of CONCURRENCY) {
    process.stderr.write(`[latency-harness] concurrency=${c} ...\n`);
    sweep.push(await runStep(c, ids));
  }
  writeFileSync(OUT, JSON.stringify(sweep, null, 2));
  writeFileSync(IDS_OUT, JSON.stringify(ids.filter(Boolean), null, 2));
  process.stderr.write(`[latency-harness] wrote ${OUT} (${sweep.length} steps) and ${IDS_OUT} (${ids.length} ids)\n`);
})();
