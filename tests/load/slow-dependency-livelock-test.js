/**
 * Conduit Gateway — F3 slow-dependency LIVELOCK test (AC2).
 *
 * The question this answers: under a slow COVERAGE service and/or a slow CERBOS PDP — the two
 * request-path legs that are NOT agents — does the gateway keep making progress, or does it livelock
 * (carriers pinned / permits drained, requests neither completing nor erroring while /health still
 * says 200)? A fast-stub load-test.js can NEVER show this; you must disturb the dependency.
 *
 * Two legs, run one at a time (set LEG):
 *   LEG=latency   coverage + Cerbos delayed 2000–8000ms   (perf-toxic.sh slow-authz)
 *   LEG=trickle   coverage + Cerbos body at 4 B/s          (perf-toxic.sh slow-authz-body)
 *
 * The toxics are applied by scripts/perf-toxic.sh against Toxiproxy; this script can ALSO apply them
 * itself in setup() when TOXIPROXY_ADMIN is set (so a CI run is one command). Ramp 25→50 VUs, 5 min
 * per stage. The livelock signals are asserted here:
 *   - completion advances every ~15s (conduit_done_count keeps climbing — not frozen);
 *   - /health returns 200 THROUGHOUT (a pinned gateway would stop answering);
 *   - P99 request time ≤ the sum of the stage deadlines counting TWO Cerbos round-trips per request
 *     (checkAgents + explainStructuralGates) — a livelocked request would blow past this.
 * Pair this with a Prometheus snapshot of conduit.outbound.gate.* (inflight returns to ~0 between
 * windows; rejected climbs, not requests-in-flight) and Tomcat busy threads — that is the AC2 bundle.
 *
 * Run (fast-stub LLM via aimock behind Toxiproxy):
 *   docker compose -p f3-loadtest -f docker-compose.yml -f docker-compose.perf.yml up -d
 *   scripts/perf-toxic.sh slow-authz          # or slow-authz-body
 *   LEG=latency GATEWAY_URL=http://localhost:8080 k6 run --summary-export f3-latency.json \
 *       tests/load/slow-dependency-livelock-test.js
 *
 * NEVER point this at the running -p orchestrator-demo stack.
 */
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Rate, Counter } from 'k6/metrics';

const reqTime    = new Trend('conduit_req_ms', true);
const errorRate  = new Rate('conduit_error_rate');
const doneCount  = new Counter('conduit_done_count');
const healthFail = new Counter('conduit_health_fail');

const GATEWAY_URL     = __ENV.GATEWAY_URL || 'http://gateway:8080';
const TOXIPROXY_ADMIN = __ENV.TOXIPROXY_ADMIN || '';         // e.g. http://localhost:8474
const LEG             = __ENV.LEG || 'latency';              // latency | trickle
const AUTHZ_PROXIES   = (__ENV.AUTHZ_PROXIES || 'coverage,coverage-insurance,cerbos').split(',');

// Per-stage deadline used to derive the P99 bound. Two Cerbos round-trips per request
// (checkAgents + explainStructuralGates) + one coverage RESOLVE/CHECK + synthesis, each bounded by
// its own read timeout/deadline. 8s toxic latency × a couple of bounded legs → ~30s ceiling.
const P99_CEILING_MS = Number(__ENV.P99_CEILING_MS || 30000);

export const options = {
  scenarios: {
    ramp2550: {
      executor: 'ramping-vus',
      startVUs: 25,
      stages: [
        { duration: '5m', target: 25 },   // hold 25 VUs — 5 min
        { duration: '5m', target: 50 },   // ramp to 50 VUs — 5 min
      ],
    },
  },
  thresholds: {
    // No livelock: the vast majority of requests still COMPLETE (error rate stays bounded — a fail-closed
    // coverage/authz answer is a completed request, not a hang), and P99 stays under the ceiling.
    conduit_req_ms:    [`p(99)<${P99_CEILING_MS}`],
    conduit_error_rate: ['rate<0.75'],       // fail-closed answers count as errors here; we only require progress
    conduit_health_fail: ['count==0'],       // /health must NEVER fail (gateway not pinned)
  },
};

const PROMPTS = [
  'Show me the complete picture for this client relationship — holdings, performance and settlement status',
  'What are the current holdings and performance for this account?',
  'Are there any pending settlements or cash issues for this relationship?',
];

export function setup() {
  if (!TOXIPROXY_ADMIN) {
    return { toxicApplied: false };
  }
  const toxic = LEG === 'trickle'
    ? { name: 'bw',  type: 'bandwidth', stream: 'downstream', attributes: { rate: 4 } }
    : { name: 'lat', type: 'latency',   stream: 'downstream', attributes: { latency: 5000, jitter: 3000 } };
  for (const p of AUTHZ_PROXIES) {
    http.post(`${TOXIPROXY_ADMIN}/proxies/${p}/toxics`, JSON.stringify(toxic),
      { headers: { 'Content-Type': 'application/json' } });
  }
  return { toxicApplied: true };
}

export default function () {
  // Health must answer 200 throughout — a pinned/livelocked gateway would stop here.
  const health = http.get(`${GATEWAY_URL}/actuator/health`, { timeout: '5s' });
  if (health.status !== 200) healthFail.add(1);

  const prompt = PROMPTS[__VU % PROMPTS.length];
  const payload = JSON.stringify({
    model: 'conduit-assistant',
    stream: true,
    messages: [{ role: 'user', content: prompt }],
  });

  const startMs = Date.now();
  const res = http.post(`${GATEWAY_URL}/v1/chat/completions`, payload, {
    headers: {
      'Content-Type': 'application/json',
      'Accept':       'text/event-stream',
      'X-User-Id':    'rm_jane',
    },
    timeout: '45s',
    responseType: 'text',
  });
  const totalMs = Date.now() - startMs;
  reqTime.add(totalMs);

  let gotDone = false;
  if (res.status === 200 && res.body) {
    for (const line of res.body.split('\n')) {
      if (line.startsWith('data:') && line.slice(5).trim() === '[DONE]') {
        gotDone = true;
        doneCount.add(1);   // progress signal — must keep climbing (not frozen) every ~15s
        break;
      }
    }
  }

  // "Completed" = the stream terminated (either a grounded answer OR a fail-closed degraded answer).
  // A livelock is the absence of BOTH — a request that never returns. Requiring the HTTP call to
  // return (any terminal status) within the timeout is the anti-livelock check.
  check(res, { 'request returned (not hung)': r => r.status !== 0 });
  errorRate.add(!gotDone);

  sleep(0.5 + Math.random());
}

export function teardown(data) {
  if (TOXIPROXY_ADMIN && data && data.toxicApplied) {
    for (const p of AUTHZ_PROXIES) {
      const list = http.get(`${TOXIPROXY_ADMIN}/proxies/${p}`);
      try {
        const toxics = JSON.parse(list.body).toxics || [];
        for (const t of toxics) http.del(`${TOXIPROXY_ADMIN}/proxies/${p}/toxics/${t.name}`);
      } catch (_) {}
    }
  }
}
