/**
 * Meridian Gateway — k6 Load Test
 *
 * Tests concurrent streaming requests through /v1/chat/completions.
 * Measures: time to first token (TTFT), total stream time, error rate.
 *
 * Run locally:  k6 run loadtest/load-test.js
 * In compose:   docker compose --profile scale run k6
 */
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Rate, Counter } from 'k6/metrics';

// ── Custom metrics ─────────────────────────────────────────────────────────
const ttft        = new Trend('meridian_ttft_ms',     true);   // time to first token
const streamTime  = new Trend('meridian_stream_ms',   true);   // total stream duration
const agentCount  = new Trend('meridian_agent_count', false);  // agents fanned out
const errorRate   = new Rate('meridian_error_rate');
const doneCount   = new Counter('meridian_done_count');

// ── Test configuration ─────────────────────────────────────────────────────
export const options = {
  scenarios: {
    steady_load: {
      executor: 'ramping-vus',
      startVUs: 1,
      stages: [
        { duration: '30s', target: 10  },   // ramp up
        { duration: '60s', target: 25  },   // hold at 25
        { duration: '30s', target: 50  },   // push to 50
        { duration: '60s', target: 50  },   // hold peak
        { duration: '30s', target: 0   },   // ramp down
      ],
    },
  },
  thresholds: {
    meridian_ttft_ms:   ['p(95)<5000'],     // 95th pct first token < 5s
    meridian_stream_ms: ['p(95)<15000'],    // 95th pct full stream < 15s
    meridian_error_rate:['rate<0.05'],      // < 5% error rate
    http_req_failed:    ['rate<0.05'],
  },
};

// ── Test prompts — rotate through to avoid caching ────────────────────────
const PROMPTS = [
  'Show me the complete picture for the Whitman Family Office — holdings, performance, risk profile, settlement status, and cash position',
  'What are the current holdings and performance for the Whitman account?',
  'Show me the risk profile and goal planning status for this client relationship',
  'Are there any pending settlements or cash management issues for the Whitman account?',
  'Give me a full overview of the Whitman portfolio — positions, P&L, and any upcoming corporate actions',
];

const GATEWAY_URL = __ENV.GATEWAY_URL || 'http://gateway:8080';

// ── Virtual user scenario ──────────────────────────────────────────────────
export default function () {
  const prompt = PROMPTS[__VU % PROMPTS.length];
  const payload = JSON.stringify({
    model: 'meridian-assistant',
    stream: true,
    messages: [{ role: 'user', content: prompt }],
  });

  const startMs = Date.now();
  let firstTokenMs = null;
  let gotDone = false;
  let chunkCount = 0;

  const res = http.post(
    `${GATEWAY_URL}/v1/chat/completions`,
    payload,
    {
      headers: {
        'Content-Type': 'application/json',
        'Accept': 'text/event-stream',
        'X-User-Id': 'rm_jane',
      },
      timeout: '20s',
      responseType: 'text',
    }
  );

  const ok = check(res, {
    'status 200':              r => r.status === 200,
    'content-type SSE':        r => (r.headers['Content-Type'] || '').includes('text/event-stream'),
  });

  if (res.status === 200 && res.body) {
    // Parse SSE chunks from the response body
    const lines = res.body.split('\n');
    for (const line of lines) {
      if (!line.startsWith('data:')) continue;
      const data = line.slice(5).trim();
      if (data === '[DONE]') {
        gotDone = true;
        doneCount.add(1);
        break;
      }
      // First content delta = first token
      if (!firstTokenMs && data && data !== '[DONE]') {
        try {
          const chunk = JSON.parse(data);
          const content = chunk?.choices?.[0]?.delta?.content;
          if (content) {
            firstTokenMs = Date.now() - startMs;
            ttft.add(firstTokenMs);
            chunkCount++;
          }
        } catch (_) {}
      } else {
        chunkCount++;
      }
    }
  }

  const totalMs = Date.now() - startMs;
  streamTime.add(totalMs);

  const success = ok && gotDone;
  errorRate.add(!success);

  // Small sleep to prevent thundering herd from a single VU
  sleep(Math.random() * 0.5 + 0.1);
}
