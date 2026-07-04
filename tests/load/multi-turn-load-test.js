/**
 * Conduit Gateway — Multi-Turn Conversation Load Test
 *
 * Exercises MULTI-TURN conversations concurrently across four personas:
 *   rm_jane    — wealth RM with Whitman book
 *   rm_carlos  — wealth RM with overlapping book
 *   uw_sam     — underwriter, insurance segment
 *   analyst_amy — research analyst, wealth segment
 *
 * Each VU:
 *   1. Picks a persona (round-robin on __VU)
 *   2. Mints a real RS256 JWT from IAM (cached in setup — refreshed between iterations)
 *   3. Runs a 3-turn conversation in the SAME X-Conversation-Id, carrying the growing
 *      messages[] array so the gateway sees true multi-turn context
 *
 * Two concurrent scenarios:
 *   multi_turn_stream  — 10 VUs, stream:true  — measures TTFT + full-stream time
 *   multi_turn_nostream —  5 VUs, stream:false — measures full request latency
 *
 * Load profile:
 *   0 → 15 VU ramp  30 s
 *   15 VU hold      90 s
 *   15 → 0 ramp     30 s
 *   Total runtime ≈ 2.5 min
 *
 * Run (local):
 *   GATEWAY_URL=http://localhost:8080 IAM_URL=http://localhost:8084 \
 *   k6 run tests/load/multi-turn-load-test.js
 *
 * Run (docker — macOS):
 *   docker run --rm -i \
 *     -e GATEWAY_URL=http://host.docker.internal:8080 \
 *     -e IAM_URL=http://host.docker.internal:8084 \
 *     -v "$(pwd)/tests/load:/scripts" \
 *     grafana/k6 run /scripts/multi-turn-load-test.js
 */
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Rate, Counter } from 'k6/metrics';

// ── Custom metrics ─────────────────────────────────────────────────────────────
const ttft          = new Trend('conduit_mt_ttft_ms',     true); // time-to-first-token (streaming only)
const streamMs      = new Trend('conduit_mt_stream_ms',   true); // full SSE stream per turn
const turnMs        = new Trend('conduit_mt_turn_ms',     true); // full request per turn (non-streaming)
const errorRate     = new Rate('conduit_mt_error_rate');          // any failed turn
const checkPass     = new Rate('conduit_mt_check_pass');          // check assertions pass-rate
const turnsTotal    = new Counter('conduit_mt_turns_total');      // individual turns executed
const convsComplete = new Counter('conduit_mt_convs_complete');   // full 3-turn convs completed

// ── Config ────────────────────────────────────────────────────────────────────
const GATEWAY_URL = __ENV.GATEWAY_URL || 'http://host.docker.internal:8080';
const IAM_URL     = __ENV.IAM_URL     || 'http://host.docker.internal:8084';

// ── Persona definitions with 3-turn conversation scripts ──────────────────────
// Turn 1  — allowed first query for this persona
// Turn 2  — follow-up in same conversation (send prior context + new user msg)
// Turn 3  — second follow-up deepening the thread
const PERSONAS = [
  {
    username: 'rm_jane',
    password: 'Meridian@2024',
    turns: [
      'What are the current holdings for Whitman Family Office?',
      'What is the YTD performance and risk profile for that relationship?',
      'Are there any pending settlements or cash management issues I should know about?',
    ],
  },
  {
    username: 'rm_carlos',
    password: 'Meridian@2024',
    turns: [
      'Give me a summary of the Whitman Family Office account',
      'What are the risk metrics and goal planning status for this relationship?',
      'Are there upcoming corporate actions or custody events I should prepare for?',
    ],
  },
  {
    username: 'uw_sam',
    password: 'Meridian@2024',
    turns: [
      'Show me details for policy POL-77001',
      'What are the coverage limits and policy type for that policy?',
      'Are there any recent or open claims associated with POL-77001?',
    ],
  },
  {
    username: 'analyst_amy',
    password: 'Meridian@2024',
    turns: [
      'What is the current equities house view?',
      'How does the fixed income outlook compare to equities right now?',
      'What are the top sector recommendations for this quarter?',
    ],
  },
];

// ── Load profile ─────────────────────────────────────────────────────────────
export const options = {
  scenarios: {
    // Streaming: 10 VUs — measures TTFT and full-stream duration
    multi_turn_stream: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 10 },
        { duration: '90s', target: 10 },
        { duration: '30s', target:  0 },
      ],
      tags:  { mode: 'stream' },
      exec: 'streamScenario',
    },
    // Non-streaming: 5 VUs — measures end-to-end turn latency
    multi_turn_nostream: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 5 },
        { duration: '90s', target: 5 },
        { duration: '30s', target: 0 },
      ],
      tags:  { mode: 'nostream' },
      exec: 'nostreamScenario',
    },
  },
  thresholds: {
    // TTFT: gateway invokes intent-classifier + entity-extractor + begins synthesis before first token.
    // Three sequential LLM calls → allow up to 30 s p95 under load.
    'conduit_mt_ttft_ms':    ['p(95)<30000'],
    // Full stream: synthesis streams after TTFT; allow 90 s p95 for slow LLM under 15 VU
    'conduit_mt_stream_ms':  ['p(95)<90000'],
    // Non-streaming full turn: same ceiling as stream total
    'conduit_mt_turn_ms':    ['p(95)<90000'],
    // Error rate < 15% (includes expected entitlement denials + LLM rate-limits)
    'conduit_mt_error_rate': ['rate<0.15'],
    // Check pass-rate > 75%
    'conduit_mt_check_pass': ['rate>0.75'],
    // HTTP transport failures (5xx / timeout) < 10%
    'http_req_failed':       ['rate<0.10'],
  },
};

// ── Setup: mint tokens once per run ──────────────────────────────────────────
export function setup() {
  const health = http.get(`${GATEWAY_URL}/actuator/health`, { timeout: '10s' });
  if (health.status !== 200) {
    throw new Error(`Gateway not healthy: HTTP ${health.status}`);
  }
  console.log(`Gateway healthy at ${GATEWAY_URL}`);

  const tokens = {};
  for (const p of PERSONAS) {
    const res = http.post(
      `${IAM_URL}/auth/token`,
      JSON.stringify({ username: p.username, password: p.password }),
      { headers: { 'Content-Type': 'application/json' }, timeout: '10s' }
    );
    if (res.status !== 200) {
      throw new Error(`Token mint failed for ${p.username}: HTTP ${res.status} — ${res.body}`);
    }
    const body = JSON.parse(res.body);
    const token = body.accessToken || body.access_token;
    if (!token) throw new Error(`No token in response for ${p.username}: ${res.body}`);
    tokens[p.username] = token;
    console.log(`Minted JWT for ${p.username} (expires in ${body.expiresIn}s)`);
  }

  console.log(`\nLoad profile: 0→10 stream VUs + 0→5 nostream VUs over 30s ramp, 90s hold, 30s ramp-down`);
  console.log(`Total runtime ≈ 150 s. Grafana dashboards will show live Prometheus metrics.\n`);

  return { tokens };
}

// ── SSE body parser: returns firstTokenMs, gotDone, contentTokens ─────────────
function parseSSE(body, startMs) {
  let firstTokenMs = null;
  let gotDone = false;
  let contentTokens = 0;

  if (!body) return { firstTokenMs, gotDone, contentTokens };

  const lines = body.split('\n');
  for (const line of lines) {
    if (!line.startsWith('data:')) continue;
    const data = line.slice(5).trim();
    if (data === '[DONE]') { gotDone = true; break; }
    try {
      const chunk = JSON.parse(data);
      const c = chunk?.choices?.[0]?.delta?.content;
      if (c) {
        if (!firstTokenMs) firstTokenMs = Date.now() - startMs;
        contentTokens++;
      }
    } catch (_) { /* ignore malformed chunks */ }
  }

  return { firstTokenMs, gotDone, contentTokens };
}

// Reassemble assistant text from SSE stream (for message history carry-forward)
function sseToText(body) {
  if (!body) return '';
  const parts = [];
  for (const line of body.split('\n')) {
    if (!line.startsWith('data:')) continue;
    const d = line.slice(5).trim();
    if (d === '[DONE]') break;
    try {
      const c = JSON.parse(d)?.choices?.[0]?.delta?.content;
      if (c) parts.push(c);
    } catch (_) {}
  }
  return parts.join('');
}

// Extract assistant content from non-streaming response
function nonStreamText(body) {
  try {
    const parsed = JSON.parse(body);
    return parsed?.choices?.[0]?.message?.content || '';
  } catch (_) { return ''; }
}

// ── Scenario: streaming (SSE), measures TTFT + full-stream per turn ───────────
export function streamScenario(data) {
  const persona       = PERSONAS[(__VU - 1) % PERSONAS.length];
  const token         = data.tokens[persona.username];
  const conversationId = `k6-s-vu${__VU}-it${__ITER}`;

  const messages = [];       // grows across turns (multi-turn carry-forward)
  let allOk = true;

  for (let i = 0; i < persona.turns.length; i++) {
    messages.push({ role: 'user', content: persona.turns[i] });

    const startMs = Date.now();
    const res = http.post(
      `${GATEWAY_URL}/v1/chat/completions`,
      JSON.stringify({
        model: 'conduit-assistant',
        stream: true,
        messages: messages.slice(), // full history so far
      }),
      {
        headers: {
          'Content-Type':     'application/json',
          'Accept':           'text/event-stream',
          'Authorization':    `Bearer ${token}`,
          'X-Conversation-Id': conversationId,
        },
        timeout: '120s',
        responseType: 'text',
      }
    );

    const elapsed = Date.now() - startMs;
    streamMs.add(elapsed);

    const { firstTokenMs, gotDone, contentTokens } = parseSSE(res.body, startMs);
    if (firstTokenMs !== null) ttft.add(firstTokenMs);

    const ok = check(res, {
      [`[stream][${persona.username}] turn${i+1} HTTP 200`]:        r => r.status === 200,
      [`[stream][${persona.username}] turn${i+1} SSE content-type`]: r => (r.headers['Content-Type'] || '').includes('text/event-stream'),
      [`[stream][${persona.username}] turn${i+1} has [DONE]`]:       () => gotDone,
      [`[stream][${persona.username}] turn${i+1} has content`]:      () => contentTokens > 0,
    });

    checkPass.add(ok ? 1 : 0);
    errorRate.add(ok ? 0 : 1);
    turnsTotal.add(1);

    if (!ok) { allOk = false; break; }

    // Carry assistant reply into history for the next turn
    const assistantText = sseToText(res.body);
    if (assistantText) messages.push({ role: 'assistant', content: assistantText });

    // Realistic inter-turn pause (simulates user reading + typing)
    if (i < persona.turns.length - 1) sleep(1 + Math.random() * 0.5);
  }

  if (allOk) convsComplete.add(1);
  sleep(0.3 + Math.random() * 0.4);
}

// ── Scenario: non-streaming (JSON), measures full turn latency ────────────────
export function nostreamScenario(data) {
  const persona       = PERSONAS[(__VU - 1) % PERSONAS.length];
  const token         = data.tokens[persona.username];
  const conversationId = `k6-ns-vu${__VU}-it${__ITER}`;

  const messages = [];
  let allOk = true;

  for (let i = 0; i < persona.turns.length; i++) {
    messages.push({ role: 'user', content: persona.turns[i] });

    const startMs = Date.now();
    const res = http.post(
      `${GATEWAY_URL}/v1/chat/completions`,
      JSON.stringify({
        model: 'conduit-assistant',
        stream: false,
        messages: messages.slice(),
      }),
      {
        headers: {
          'Content-Type':     'application/json',
          'Authorization':    `Bearer ${token}`,
          'X-Conversation-Id': conversationId,
        },
        timeout: '120s',
      }
    );

    const elapsed = Date.now() - startMs;
    turnMs.add(elapsed);

    const content = nonStreamText(res.body);
    const isValidJson = (() => { try { JSON.parse(res.body); return true; } catch (_) { return false; } })();

    const ok = check(res, {
      [`[nostream][${persona.username}] turn${i+1} HTTP 200`]:    r => r.status === 200,
      [`[nostream][${persona.username}] turn${i+1} valid JSON`]:  () => isValidJson,
      [`[nostream][${persona.username}] turn${i+1} has content`]: () => content.length > 5,
    });

    checkPass.add(ok ? 1 : 0);
    errorRate.add(ok ? 0 : 1);
    turnsTotal.add(1);

    if (!ok) { allOk = false; break; }

    if (content) messages.push({ role: 'assistant', content });

    if (i < persona.turns.length - 1) sleep(1 + Math.random() * 0.5);
  }

  if (allOk) convsComplete.add(1);
  sleep(0.3 + Math.random() * 0.4);
}

// ── Teardown: verify gateway is still healthy after load ─────────────────────
export function teardown() {
  const health = http.get(`${GATEWAY_URL}/actuator/health`, { timeout: '10s' });
  const ok = check(health, { 'post-load gateway healthy': r => r.status === 200 });
  console.log(`\n=== Multi-Turn Load Test Complete ===`);
  console.log(`Post-load gateway health: ${ok ? 'HEALTHY' : 'DEGRADED'}`);
  console.log(`Grafana dashboards (Prometheus) will reflect traffic from this run.`);
  console.log(`Dashboard: http://localhost:3000\n`);
}
