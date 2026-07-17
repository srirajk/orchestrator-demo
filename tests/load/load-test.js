/**
 * Conduit Gateway — k6 Phased Load Test
 *
 * Four sequential phases that match the build milestones:
 *   Phase 1 (smoke)   — 1 VU, baseline correctness
 *   Phase 2 (ramp)    — ramp to 10 VU, measure TTFT
 *   Phase 3 (load)    — hold 25 VU for 60s, find steady-state throughput
 *   Phase 4 (stress)  — burst to 50 VU, verify error rate stays low
 *
 * Run locally:  GATEWAY_URL=http://localhost:8080 k6 run tests/load/load-test.js
 * Smoke only:   GATEWAY_URL=http://localhost:8080 k6 run tests/load/smoke-test.js
 * In compose:   docker compose --profile scale run k6
 */
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Rate, Counter } from 'k6/metrics';

// ── Custom metrics ─────────────────────────────────────────────────────────
const ttft       = new Trend('conduit_ttft_ms',     true);
const streamTime = new Trend('conduit_stream_ms',   true);
const agentCount = new Trend('conduit_agent_count', false);
const errorRate  = new Rate('conduit_error_rate');
const doneCount  = new Counter('conduit_done_count');

// ── Configuration ──────────────────────────────────────────────────────────
export const options = {
  scenarios: {
    // Phase 1: smoke — 1 VU verifies baseline correctness
    smoke: {
      executor: 'constant-vus',
      vus: 1,
      duration: '20s',
      tags: { phase: 'smoke' },
    },
    // Phase 2: ramp — verify latency profile under light load
    ramp: {
      executor: 'ramping-vus',
      startTime: '25s',
      startVUs: 1,
      stages: [
        { duration: '20s', target: 10 },
        { duration: '20s', target: 10 },
        { duration: '10s', target: 0  },
      ],
      tags: { phase: 'ramp' },
    },
    // Phase 3: load — steady-state throughput at 25 VU
    load: {
      executor: 'constant-vus',
      vus: 25,
      startTime: '80s',
      duration: '60s',
      tags: { phase: 'load' },
    },
    // Phase 4: stress — burst to 50 VU, verify error rate holds
    stress: {
      executor: 'ramping-vus',
      startTime: '145s',
      startVUs: 25,
      stages: [
        { duration: '15s', target: 50 },
        { duration: '30s', target: 50 },
        { duration: '15s', target: 0  },
      ],
      tags: { phase: 'stress' },
    },
  },
  thresholds: {
    // TTFT: realistically 3 sequential Z.AI LLM calls per request (intent + extract + synthesize).
    // Under 25 VU sustained load, p95 TTFT observed at ~14s — set threshold at 20s to catch
    // genuine degradation vs normal LLM pipeline latency.
    conduit_ttft_ms:    ['p(95)<20000'],
    // Full stream: synthesis is streamed, so 30s covers slow synthesis under peak load
    conduit_stream_ms:  ['p(95)<30000'],
    // Error rate: < 5% under all phases including stress
    conduit_error_rate: ['rate<0.05'],
    http_req_failed:     ['rate<0.05'],
  },
};

// ── Test prompts — rotate so the resolver sees different queries ───────────
const PROMPTS = [
  'Show me the complete picture for the Whitman Family Office — holdings, performance, risk profile, settlement status, and cash position',
  'What are the current holdings and performance for the Whitman account?',
  'Show me the risk profile and goal planning status for this client relationship',
  'Are there any pending settlements or cash management issues for the Whitman account?',
  'Give me a full overview of the Whitman portfolio — positions, P&L, and any upcoming corporate actions',
  'What is the NAV for fund FND-7781?',
  'What custody positions does the Whitman Family Office hold?',
];

const GATEWAY_URL = __ENV.GATEWAY_URL || 'http://gateway:8080';

// ── Virtual user scenario ──────────────────────────────────────────────────
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
  let contentChunks = 0;

  const res = http.post(
    `${GATEWAY_URL}/v1/chat/completions`,
    payload,
    {
      headers: {
        'Content-Type':  'application/json',
        'Accept':        'text/event-stream',
      },
      timeout: '35s',
      responseType: 'text',
    }
  );

  const ok = check(res, {
    'HTTP 200':             r => r.status === 200,
    'SSE content-type':     r => (r.headers['Content-Type'] || '').includes('text/event-stream'),
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
        if (content) {
          if (!firstTokenMs) {
            firstTokenMs = Date.now() - startMs;
            ttft.add(firstTokenMs);
          }
          contentChunks++;
        }
        // Read agent count from the last chunk's usage metadata (if present)
        const agentsUsed = chunk?.usage?.conduit_agent_count;
        if (agentsUsed) agentCount.add(agentsUsed);
      } catch (_) {}
    }
  }

  const totalMs = Date.now() - startMs;
  streamTime.add(totalMs);
  errorRate.add(!(ok && gotDone));

  // Pacing: small jitter to avoid thundering herd from individual VUs
  sleep(Math.random() * 0.3 + 0.1);
}

// ── Setup: verify the gateway is reachable before the test starts ──────────
export function setup() {
  const res = http.get(`${GATEWAY_URL}/v1/models`, { timeout: '10s' });
  if (res.status !== 200) {
    throw new Error(`Gateway not ready: GET /v1/models returned ${res.status}. Start the stack first.`);
  }
  console.log(`Gateway ready at ${GATEWAY_URL}`);
}
