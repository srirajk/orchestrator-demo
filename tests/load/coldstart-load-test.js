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
// HTTP 200 is NOT success. Under load the gateway can stream a fluent "data unavailable"
// answer with HTTP 200 when an agent's circuit breaker is open. Asserting only the status
// code reports a false green. These two track whether the ANSWER actually carried the data.
const degradedRate = new Rate('conduit_degraded_rate');
const degradedCount = new Counter('conduit_degraded_count');

const GATEWAY_URL = __ENV.GATEWAY_URL || 'http://gateway:8080';
const AUTH_TOKEN   = __ENV.AUTH_TOKEN || '';
const PATH_KIND    = __ENV.PATH_KIND || 'flat';
const VUS          = parseInt(__ENV.VUS || '1', 10);
const DURATION     = __ENV.DURATION || '20s';

// Verified single-agent (flat) prompts — each resolves to exactly one agent per the
// demo query catalog (servicing.nav / servicing.corporate_actions / wealth.market_research).
// Each prompt carries the domain data its answer MUST contain. `expect` is checked against the
// assembled answer text; if it's absent the answer is degraded even when the status is 200.
const FLAT_PROMPTS = [
  { text: "What's the latest NAV on fund FND-7781?",
    expect: /128\.45/ },
  { text: 'Any upcoming corporate actions — dividends or splits — for REL-00042?',
    expect: /AAPL|CA-2245|dividend/i },
  { text: "What is Meridian's house view on equities this quarter?",
    expect: /equit|neutral|constructive/i },
];

// Verified multi-agent fan-in (DAG-ish) prompts — settlement + custody fan-in,
// and the concentration analytics query (smoke-tested separately).
const DAG_PROMPTS = [
  { text: 'Show me pending settlements and custody positions for REL-00042.',
    expect: /settle|custod/i },
  { text: 'What is the concentration risk in the Whitman Family Office holdings?',
    expect: /concentration|%|position/i },
  { text: 'Give me a full overview of the Whitman relationship REL-00042.',
    expect: /whitman|holding|portfolio/i },
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
  // A run that streams "data unavailable" at HTTP 200 must FAIL, not pass silently.
  // DEGRADED_MAX can be raised deliberately when characterising a known-degraded build.
  thresholds: {
    conduit_error_rate:    [`rate<${__ENV.ERROR_MAX || '0.01'}`],
    conduit_degraded_rate: [`rate<${__ENV.DEGRADED_MAX || '0.01'}`],
  },
};

export default function () {
  const spec = PROMPTS[__VU % PROMPTS.length];
  const prompt = spec.text;
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

  let answer = '';

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
          answer += content;
          if (!firstTokenMs) {
            firstTokenMs = Date.now() - startMs;
            ttft.add(firstTokenMs);
          }
        }
      } catch (_) {}
    }
  }

  // An answer is DEGRADED when the data the prompt asked for is absent, or when the gateway
  // says the data could not be retrieved. Both arrive as HTTP 200 with a well-formed stream.
  const saysUnavailable = /unavailable|not available|could not (be )?(retrieve|obtain)|no data/i.test(answer);
  const hasExpectedData = spec.expect.test(answer);
  const degraded = res.status === 200 && gotDone && (saysUnavailable || !hasExpectedData);

  const ok = check(res, {
    'HTTP 200': r => r.status === 200,
    'answer carries the requested data': () => hasExpectedData && !saysUnavailable,
  });

  if (degraded) {
    degradedCount.add(1);
    console.warn(`DEGRADED [${prompt.slice(0, 40)}] -> ${answer.slice(0, 90)}`);
  }
  degradedRate.add(degraded);

  streamTime.add(Date.now() - startMs);
  errorRate.add(!(ok && gotDone));

  sleep(Math.random() * 0.2 + 0.05);
}
