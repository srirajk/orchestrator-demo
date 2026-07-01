/**
 * Conduit Gateway — Lightweight k6 Load Test (demo / CI)
 * Max 10 VUs, 2 minutes total. Measures TTFT p95 and error rate.
 */
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Rate } from 'k6/metrics';

const ttft       = new Trend('conduit_ttft_ms', true);
const streamTime = new Trend('conduit_stream_ms', true);
const errorRate  = new Rate('conduit_error_rate');

export const options = {
  scenarios: {
    ramp: {
      executor: 'ramping-vus',
      startVUs: 1,
      stages: [
        { duration: '20s', target: 5  },
        { duration: '40s', target: 10 },
        { duration: '20s', target: 0  },
      ],
    },
  },
  thresholds: {
    conduit_ttft_ms:   ['p(95)<8000'],
    conduit_stream_ms: ['p(95)<30000'],
    conduit_error_rate:['rate<0.10'],
  },
};

const PROMPTS = [
  'What are the current holdings for the Whitman Family Office?',
  'Show me settlement status for recent trades',
  'What is the risk profile and goal planning status for this relationship?',
  'Give me the cash management position for this account',
];

const GATEWAY_URL = __ENV.GATEWAY_URL || 'http://localhost:8080';

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

  const res = http.post(`${GATEWAY_URL}/v1/chat/completions`, payload, {
    headers: {
      'Content-Type': 'application/json',
      'Accept': 'text/event-stream',
      'X-User-Id': 'rm_jane',
    },
    timeout: '35s',
    responseType: 'text',
  });

  const ok = check(res, {
    'status 200': r => r.status === 200,
    'SSE stream': r => (r.headers['Content-Type'] || '').includes('text/event-stream'),
  });

  if (res.status === 200 && res.body) {
    for (const line of res.body.split('\n')) {
      if (!line.startsWith('data:')) continue;
      const data = line.slice(5).trim();
      if (data === '[DONE]') { gotDone = true; break; }
      if (!firstTokenMs) {
        try {
          const c = JSON.parse(data)?.choices?.[0]?.delta?.content;
          if (c) { firstTokenMs = Date.now() - startMs; ttft.add(firstTokenMs); }
        } catch (_) {}
      }
    }
  }

  streamTime.add(Date.now() - startMs);
  errorRate.add(!(ok && gotDone));
  sleep(0.5 + Math.random() * 0.5);
}
