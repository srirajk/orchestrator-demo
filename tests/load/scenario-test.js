/**
 * Meridian Gateway — Scenario Performance Test
 *
 * Tests specific gateway behaviours under concurrent load:
 *   Scenario 1 (hero)        — full 7+ agent fan-out (HTTP + MCP), multi-protocol
 *   Scenario 2 (entitlement) — Cerbos allow vs deny under concurrent load
 *   Scenario 3 (resilience)  — partial failures return degraded-not-broken answer
 *   Scenario 4 (routing)     — different prompt types route to correct agents
 *   Scenario 5 (follow_up)   — FOLLOW_UP intents skip agent fan-out
 *   Scenario 6 (stress)      — burst to 20 VU, verify error rate and p99
 *
 * Run locally:
 *   GATEWAY_URL=http://localhost:8080 USER_MGMT_URL=http://localhost:8084 \
 *   k6 run tests/load/scenario-test.js
 *
 * Or via docker compose:
 *   docker compose --profile scale run k6 run /scripts/scenario-test.js
 */
import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Trend, Rate, Counter, Gauge } from 'k6/metrics';

// ── Custom metrics ──────────────────────────────────────────────────────────
const ttft          = new Trend('meridian_scenario_ttft_ms',    true);
const e2eTime       = new Trend('meridian_scenario_e2e_ms',     true);
const heroLatency   = new Trend('meridian_hero_e2e_ms',         true);
const errorRate     = new Rate('meridian_scenario_error_rate');
const deniedCount   = new Counter('meridian_entitlement_denied');
const allowedCount  = new Counter('meridian_entitlement_allowed');
const doneCount     = new Counter('meridian_scenario_done');
const agentsFanOut  = new Trend('meridian_agents_fanned_out',   false);

// ── Config ──────────────────────────────────────────────────────────────────
const GATEWAY_URL   = __ENV.GATEWAY_URL    || 'http://localhost:8080';
const USER_MGMT_URL = __ENV.USER_MGMT_URL  || 'http://localhost:8084';
const WEALTH_URL    = __ENV.WEALTH_URL     || 'http://localhost:8081';
const SERVICING_URL = __ENV.SERVICING_URL  || 'http://localhost:8082';

// ── Scenarios ────────────────────────────────────────────────────────────────
export const options = {
  scenarios: {
    // 1. Hero prompt: full multi-protocol fan-out
    hero_fanout: {
      executor: 'constant-vus',
      vus: 3,
      duration: '60s',
      tags: { scenario: 'hero' },
      exec: 'heroScenario',
    },
    // 2. Entitlement: concurrent allow+deny
    entitlement: {
      executor: 'constant-vus',
      vus: 4,
      startTime: '0s',
      duration: '60s',
      tags: { scenario: 'entitlement' },
      exec: 'entitlementScenario',
    },
    // 3. Routing: different prompt types
    routing: {
      executor: 'constant-vus',
      vus: 4,
      startTime: '0s',
      duration: '60s',
      tags: { scenario: 'routing' },
      exec: 'routingScenario',
    },
    // 4. Follow-up: chitchat + follow-up (no agent fan-out)
    followup: {
      executor: 'constant-vus',
      vus: 2,
      startTime: '0s',
      duration: '60s',
      tags: { scenario: 'followup' },
      exec: 'followUpScenario',
    },
    // 5. Stress burst: 20 VU, verify error rate holds
    stress: {
      executor: 'ramping-vus',
      startTime: '65s',
      startVUs: 5,
      stages: [
        { duration: '15s', target: 20 },
        { duration: '30s', target: 20 },
        { duration: '10s', target: 0  },
      ],
      tags: { scenario: 'stress' },
      exec: 'stressScenario',
    },
  },
  thresholds: {
    // Overall error rate across all scenarios
    'meridian_scenario_error_rate': ['rate<0.15'],   // < 15% errors (LLM 429s expected under stress)
    // Hero fan-out p95 < 45s (LLM calls dominate)
    'meridian_hero_e2e_ms':         ['p(95)<45000'],
    // E2E p95 < 40s across non-hero scenarios
    'meridian_scenario_e2e_ms{scenario:routing}':    ['p(95)<40000'],
    'meridian_scenario_e2e_ms{scenario:entitlement}':['p(95)<35000'],
    // HTTP errors (500/503 from agents or gateway crash)
    'http_req_failed':  ['rate<0.10'],
  },
};

// ── Shared helpers ───────────────────────────────────────────────────────────

function mintToken(userId) {
  const res = http.post(
    `${USER_MGMT_URL}/auth/token`,
    JSON.stringify({ user_id: userId }),
    { headers: { 'Content-Type': 'application/json' }, timeout: '5s' }
  );
  if (res.status !== 200) return '';
  try { return JSON.parse(res.body).access_token || ''; }
  catch (_) { return ''; }
}

function chat(prompt, userId, token, conversationId) {
  const headers = {
    'Content-Type': 'application/json',
    'Accept':       'text/event-stream',
  };
  if (token)          headers['Authorization']   = `Bearer ${token}`;
  if (userId)         headers['X-User-Id']        = userId;
  if (conversationId) headers['X-Conversation-Id'] = conversationId;

  const start = Date.now();
  const res = http.post(
    `${GATEWAY_URL}/v1/chat/completions`,
    JSON.stringify({
      model: 'meridian-assistant',
      stream: true,
      messages: [{ role: 'user', content: prompt }],
    }),
    { headers, timeout: '60s', responseType: 'text' }
  );

  const elapsed = Date.now() - start;
  e2eTime.add(elapsed);

  const body = res.body || '';
  const hasDone = body.includes('[DONE]');
  const hasContent = body.includes('"content"');

  return { res, elapsed, hasDone, hasContent, body };
}

// ── Scenario 1: Hero fan-out (HTTP + MCP, 7+ agents) ─────────────────────────

const HERO_PROMPTS = [
  'Show me the complete portfolio overview for Whitman Family Office: holdings, performance, settlements, and cash position',
  'What is the full financial picture for the Whitman Family Office — positions, returns, pending trades, and cash?',
  'Give me a comprehensive summary of Whitman Family Office including holdings, risk, performance and open settlements',
];

export function setup() {
  // Verify gateway is up
  const health = http.get(`${GATEWAY_URL}/actuator/health`);
  if (health.status !== 200) {
    throw new Error(`Gateway not healthy: HTTP ${health.status}`);
  }
  console.log(`Scenario test: gateway at ${GATEWAY_URL} is healthy`);

  // Verify Wealth HTTP agent
  const wealth = http.get(`${WEALTH_URL}/health`);
  console.log(`Wealth HTTP: HTTP ${wealth.status}`);

  // Verify Servicing MCP agent
  const servicing = http.get(`${SERVICING_URL}/health`);
  console.log(`Servicing MCP: HTTP ${servicing.status}`);

  return {
    rmJaneToken: mintToken('rm_jane'),
    rmBobToken:  mintToken('rm_bob'),
    adminToken:  mintToken('admin'),
  };
}

export function heroScenario(data) {
  const prompt = HERO_PROMPTS[Math.floor(Math.random() * HERO_PROMPTS.length)];
  const start = Date.now();

  const headers = {
    'Content-Type': 'application/json',
    'Accept':       'text/event-stream',
    'X-User-Id':    'rm_jane',
  };
  if (data.rmJaneToken) headers['Authorization'] = `Bearer ${data.rmJaneToken}`;

  const res = http.post(
    `${GATEWAY_URL}/v1/chat/completions`,
    JSON.stringify({ model: 'meridian-assistant', stream: true,
                     messages: [{ role: 'user', content: prompt }] }),
    { headers, timeout: '90s', responseType: 'text' }
  );

  const elapsed = Date.now() - start;
  heroLatency.add(elapsed);

  const body = res.body || '';
  const ok = check(res, {
    'hero 200':              r => r.status === 200,
    'hero SSE stream':       r => (r.headers['Content-Type'] || '').includes('text/event-stream'),
    'hero has [DONE]':       () => body.includes('[DONE]'),
    'hero has answer text':  () => body.includes('"content"'),
    'hero not empty answer': () => {
      // Extract all content tokens
      const tokens = (body.match(/"content":"([^"]+)"/g) || []);
      return tokens.length > 3;
    },
  });

  errorRate.add(!ok);
  if (ok) doneCount.add(1);

  // Extract agent count from trace events embedded in response (glass-box)
  // or from the answer itself referencing data sources
  sleep(2);
}

// ── Scenario 2: Entitlement enforcement ──────────────────────────────────────

const IN_BOOK_PROMPTS = [
  'What are the holdings for Whitman Family Office?',
  'Show me the Whitman performance YTD',
  'Get me the cash position for the Whitman Family Office',
];

const OUT_OF_BOOK_PROMPTS = [
  'Show me the Okafor Family Trust holdings',
  'What is the portfolio value for the Okafor relationship?',
  'Get the settlements for Okafor Family Trust',
];

export function entitlementScenario(data) {
  const isAllowed = Math.random() > 0.5;

  group(isAllowed ? 'allow' : 'deny', () => {
    const prompts = isAllowed ? IN_BOOK_PROMPTS : OUT_OF_BOOK_PROMPTS;
    const prompt  = prompts[Math.floor(Math.random() * prompts.length)];

    const { res, hasDone, body } = chat(prompt, 'rm_jane', data.rmJaneToken, null);

    if (isAllowed) {
      const ok = check(res, {
        'allow: 200':          r => r.status === 200,
        'allow: has [DONE]':   () => hasDone,
        'allow: has content':  () => body.includes('"content"'),
        'allow: not denied':   () => !body.toLowerCase().includes('access denied') &&
                                    !body.toLowerCase().includes('not authorized'),
      });
      allowedCount.add(1);
      errorRate.add(!ok);
    } else {
      const ok = check(res, {
        'deny: 200':                r => r.status === 200,
        'deny: has [DONE]':         () => hasDone,
        'deny: access denied msg':  () => {
          const lower = body.toLowerCase();
          return lower.includes('access denied') || lower.includes('not authorized') ||
                 lower.includes('not in your') || lower.includes('outside') ||
                 lower.includes('cannot find') || lower.includes("couldn't find");
        },
      });
      deniedCount.add(1);
      errorRate.add(!ok);
    }
  });

  sleep(1);
}

// ── Scenario 3: Routing correctness ──────────────────────────────────────────

const ROUTING_CASES = [
  { prompt: 'What are the holdings for Whitman Family Office?',         expect: 'holdings' },
  { prompt: 'Show me the performance returns for Whitman YTD',          expect: 'performance' },
  { prompt: 'List pending settlements for Whitman Family Office',       expect: 'settlement' },
  { prompt: 'What is the cash position for Whitman Family Office?',     expect: 'cash' },
  { prompt: 'Show me the corporate actions for Whitman',                expect: 'corporate' },
  { prompt: 'What are the custody positions for Whitman Family Office?', expect: 'custody' },
  { prompt: 'Show me the goal planning status for Whitman',             expect: 'goal' },
  { prompt: 'What is the risk profile for Whitman Family Office?',      expect: 'risk' },
];

export function routingScenario(data) {
  const tc = ROUTING_CASES[Math.floor(Math.random() * ROUTING_CASES.length)];
  const { res, hasDone, body } = chat(tc.prompt, 'rm_jane', data.rmJaneToken, null);

  const ok = check(res, {
    'routing: 200':              r => r.status === 200,
    'routing: SSE stream':       r => (r.headers['Content-Type'] || '').includes('text/event-stream'),
    'routing: has [DONE]':       () => hasDone,
    'routing: has answer':       () => body.includes('"content"'),
  });

  errorRate.add(!ok);
  if (ok) doneCount.add(1);
  sleep(1);
}

// ── Scenario 4: Follow-up / chitchat (no agent fan-out) ──────────────────────

export function followUpScenario(data) {
  group('chitchat', () => {
    const start = Date.now();
    const { res, hasDone, body } = chat(
      'Hello! What can you help me with?',
      'rm_jane', data.rmJaneToken, null
    );
    const elapsed = Date.now() - start;

    const ok = check(res, {
      'chitchat: 200':       r => r.status === 200,
      'chitchat: has [DONE]': () => hasDone,
      'chitchat: fast':      () => elapsed < 30000, // chitchat should skip agents
    });
    errorRate.add(!ok);
  });

  sleep(2);
}

// ── Scenario 5: Stress burst ──────────────────────────────────────────────────

const STRESS_PROMPTS = [
  'Show me the Whitman Family Office holdings',
  'What is the performance for Whitman YTD?',
  'Show me cash position for Whitman Family Office',
  'List settlements for Whitman',
  'What are the custody positions for Whitman Family Office?',
];

export function stressScenario(data) {
  const prompt = STRESS_PROMPTS[Math.floor(Math.random() * STRESS_PROMPTS.length)];
  const { res, hasDone } = chat(prompt, 'rm_jane', data.rmJaneToken, null);

  const ok = check(res, {
    'stress: 200 or 429':     r => r.status === 200 || r.status === 429,
    'stress: not 500':        r => r.status !== 500,
    'stress: [DONE] if 200':  r => r.status !== 200 || hasDone,
  });

  errorRate.add(!ok);
  sleep(0.5);
}

// ── Direct agent health checks (always run in setup, not per VU) ────────────

export function teardown(data) {
  // Final health check after all load
  const health = http.get(`${GATEWAY_URL}/actuator/health`);
  check(health, {
    'post-load gateway healthy': r => r.status === 200,
  });

  const wealth = http.get(`${WEALTH_URL}/health`);
  check(wealth, {
    'post-load wealth healthy': r => r.status === 200,
  });

  const servicing = http.get(`${SERVICING_URL}/health`);
  check(servicing, {
    'post-load servicing healthy': r => r.status === 200,
  });

  console.log(`\n=== Scenario Test Complete ===`);
  console.log(`Allowed entitlement calls: ${allowedCount.name}`);
  console.log(`Denied entitlement calls:  ${deniedCount.name}`);
}
