/**
 * Conduit Gateway — Cold-start robustness load test (flat vs DAG path).
 *
 * Ad-hoc script for an operator cold-start/load check. Not part of the permanent load
 * harness (see load-test.js / load-test-light.js for those) — this one isolates the
 * FLAT (single-agent lookup) path from the DAG (multi-agent fan-in) path so each gets
 * its own concurrency-vs-latency table, run at a single fixed concurrency per invocation
 * (constant-vus). Invoke once per (path, concurrency) pair from the driver script.
 *
 * Env:
 *   GATEWAY_URL   default http://gateway:8080
 *   AUTH_TOKEN    JWT bearer token for rm_jane (minted via IAM /auth/token)
 *   PATH_KIND     "flat" | "dag"
 *   VUS           concurrency (default 1)
 *   DURATION      e.g. "20s" (default 20s)
 */
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Rate, Counter } from 'k6/metrics';

const ttft       = new Trend('conduit_ttft_ms',     true);
const streamTime = new Trend('conduit_stream_ms',   true);
const errorRate  = new Rate('conduit_error_rate');
const doneCount  = new Counter('conduit_done_count');

const GATEWAY_URL = __ENV.GATEWAY_URL || 'http://gateway:8080';
const AUTH_TOKEN   = __ENV.AUTH_TOKEN || '';
const PATH_KIND    = __ENV.PATH_KIND || 'flat';
const VUS          = parseInt(__ENV.VUS || '1', 10);
const DURATION     = __ENV.DURATION || '20s';

// Verified single-agent (flat) prompts — each resolves to exactly one agent per the
// demo query catalog (servicing.nav / servicing.corporate_actions / wealth.market_research).
const FLAT_PROMPTS = [
  "What's the latest NAV on fund FND-7781?",
  'Any upcoming corporate actions — dividends or splits — for REL-00042?',
  "What is Meridian's house view on equities this quarter?",
];

// Verified multi-agent fan-in (DAG-ish) prompts — settlement + custody fan-in,
// and the concentration analytics query (smoke-tested separately).
const DAG_PROMPTS = [
  'Show me pending settlements and custody positions for REL-00042.',
  'What is the concentration risk in the Whitman Family Office holdings?',
  'Give me a full overview of the Whitman relationship REL-00042.',
];

const PROMPTS = PATH_KIND === 'dag' ? DAG_PROMPTS : FLAT_PROMPTS;

export const options = {
  scenarios: {
    run: {
      executor: 'constant-vus',
      vus: VUS,
      duration: DURATION,
      tags: { path_kind: PATH_KIND, vus: String(VUS) },
    },
  },
};

export default function () {
  const prompt = PROMPTS[__VU % PROMPTS.length];
  const payload = JSON.stringify({
    model: 'conduit-assistant',
    stream: true,
    messages: [{ role: 'user', content: prompt }],
  });

  const startMs = Date.now();
  let firstTokenMs = null;
  let gotDone = false;

  const headers = {
    'Content-Type': 'application/json',
    'Accept': 'text/event-stream',
  };
  if (AUTH_TOKEN) {
    headers['Authorization'] = `Bearer ${AUTH_TOKEN}`;
  } else {
    headers['X-User-Id'] = 'rm_jane';
  }

  const res = http.post(`${GATEWAY_URL}/v1/chat/completions`, payload, {
    headers,
    timeout: '45s',
    responseType: 'text',
  });

  const ok = check(res, {
    'HTTP 200': r => r.status === 200,
  });

  if (res.status === 200 && res.body) {
    const lines = res.body.split('\n');
    for (const line of lines) {
      if (!line.startsWith('data:')) continue;
      const data = line.slice(5).trim();
      if (data === '[DONE]') {
        gotDone = true;
        doneCount.add(1);
        break;
      }
      try {
        const chunk = JSON.parse(data);
        const content = chunk?.choices?.[0]?.delta?.content;
        if (content && !firstTokenMs) {
          firstTokenMs = Date.now() - startMs;
          ttft.add(firstTokenMs);
        }
      } catch (_) {}
    }
  }

  streamTime.add(Date.now() - startMs);
  errorRate.add(!(ok && gotDone));

  sleep(Math.random() * 0.2 + 0.05);
}
