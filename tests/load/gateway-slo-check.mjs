#!/usr/bin/env node
/**
 * Gateway SLO snapshot: drives fixed gateway load and reports latency, throughput,
 * error rate, and peak saturation. Prompts mirror coldstart-load-test.js so flat/DAG
 * runs are comparable without requiring k6 for this quick operator check.
 */
const GATEWAY_URL = process.env.GATEWAY_URL || 'http://localhost:8080';
const PROMETHEUS_URL = process.env.PROMETHEUS_URL || 'http://localhost:9090';
const AUTH_TOKEN = process.env.AUTH_TOKEN || '';
const PATH_KIND = process.env.PATH_KIND || 'dag';
const CONCURRENCY = Number.parseInt(process.env.CONCURRENCY || process.env.VUS || '3', 10);
const REQUESTS = Number.parseInt(process.env.REQUESTS || String(CONCURRENCY * 3), 10);
const TIMEOUT_MS = Number.parseInt(process.env.TIMEOUT_MS || '150000', 10);

const FLAT_PROMPTS = [
  "What's the latest NAV on fund FND-7781?",
  'Any upcoming corporate actions — dividends or splits — for REL-00042?',
  "What is Meridian's house view on equities this quarter?",
];

const DAG_PROMPTS = [
  'Show me pending settlements and custody positions for REL-00042.',
  'What is the concentration risk in the Whitman Family Office holdings?',
  'Give me a full overview of the Whitman relationship REL-00042.',
];

const PROMPTS = PATH_KIND === 'flat' ? FLAT_PROMPTS : DAG_PROMPTS;

function percentile(values, p) {
  if (values.length === 0) return 0;
  const sorted = [...values].sort((a, b) => a - b);
  const idx = Math.min(sorted.length - 1, Math.ceil((p / 100) * sorted.length) - 1);
  return sorted[idx];
}

async function gatewayRequest(i) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), TIMEOUT_MS);
  const started = Date.now();
  try {
    const headers = {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
    };
    if (AUTH_TOKEN) {
      headers.Authorization = `Bearer ${AUTH_TOKEN}`;
    }
    // No AUTH_TOKEN → anonymous. Identity comes ONLY from the verified JWT; no header identity
    // path exists (Axiom A1).
    const response = await fetch(`${GATEWAY_URL}/v1/chat/completions`, {
      method: 'POST',
      headers,
      body: JSON.stringify({
        model: 'conduit-assistant',
        stream: true,
        messages: [{ role: 'user', content: PROMPTS[i % PROMPTS.length] }],
      }),
      signal: controller.signal,
    });
    const body = await response.text();
    return {
      ok: response.status === 200 && body.includes('data: [DONE]'),
      status: response.status,
      latency_ms: Date.now() - started,
    };
  } catch (error) {
    return { ok: false, status: 'error', error: String(error), latency_ms: Date.now() - started };
  } finally {
    clearTimeout(timer);
  }
}

async function prometheusQuery(query) {
  const url = new URL('/api/v1/query', PROMETHEUS_URL);
  url.searchParams.set('query', query);
  const response = await fetch(url);
  if (!response.ok) return [];
  const body = await response.json();
  return body?.data?.result || [];
}

async function samplePeakSaturation(stop) {
  let peak = 0;
  while (!stop.done) {
    const response = await fetch(`${GATEWAY_URL}/actuator/prometheus`).catch(() => null);
    if (response?.ok) {
      const text = await response.text();
      const match = text.match(/^conduit_gateway_inflight_requests(?:\{[^}]*\})?\s+([0-9.]+)$/m);
      if (match) {
        const value = Number.parseFloat(match[1]);
        if (value > peak) peak = value;
      }
    }
    await new Promise(resolve => setTimeout(resolve, 250));
  }
  return peak;
}

async function main() {
  const stop = { done: false };
  const saturation = samplePeakSaturation(stop);
  const started = Date.now();
  const results = [];
  let next = 0;
  async function worker() {
    while (next < REQUESTS) {
      const idx = next++;
      results.push(await gatewayRequest(idx));
    }
  }
  await Promise.all(Array.from({ length: CONCURRENCY }, () => worker()));
  stop.done = true;
  const peakSaturation = await saturation;
  const elapsedSeconds = (Date.now() - started) / 1000;
  const latencies = results.map(result => result.latency_ms);
  const ok = results.filter(result => result.ok).length;
  const snapshot = {
    path_kind: PATH_KIND,
    requests: results.length,
    ok,
    errors: results.length - ok,
    error_rate: results.length === 0 ? 1 : (results.length - ok) / results.length,
    throughput_rps: results.length / elapsedSeconds,
    latency_ms: {
      p50: percentile(latencies, 50),
      p95: percentile(latencies, 95),
      p99: percentile(latencies, 99),
    },
    peak_saturation: peakSaturation,
  };
  console.log(JSON.stringify(snapshot, null, 2));
  process.exit(snapshot.errors === 0 && snapshot.peak_saturation > 0 ? 0 : 1);
}

main().catch(error => {
  console.error(error);
  process.exit(1);
});
