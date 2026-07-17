/**
 * Conduit Gateway — Smoke Test (fast CI check)
 *
 * 3 VUs, 30 seconds. Verifies the gateway is up and serving SSE responses.
 * Runs before the full load test to catch obvious startup failures.
 *
 * Run:  GATEWAY_URL=http://localhost:8080 k6 run tests/load/smoke-test.js
 */
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate } from 'k6/metrics';

const errorRate = new Rate('smoke_error_rate');

export const options = {
  vus: 3,
  duration: '30s',
  thresholds: {
    smoke_error_rate: ['rate<0.01'],    // < 1% errors in smoke
    http_req_failed:  ['rate<0.01'],
    http_req_duration: ['p(95)<25000'], // 95th pct response < 25s
  },
};

const GATEWAY_URL = __ENV.GATEWAY_URL || 'http://gateway:8080';

export function setup() {
  // Verify /v1/models and /actuator/health before starting
  const modelsRes = http.get(`${GATEWAY_URL}/v1/models`);
  const healthRes = http.get(`${GATEWAY_URL}/actuator/health`);
  if (modelsRes.status !== 200 || healthRes.status !== 200) {
    throw new Error(
      `Gateway not ready — /v1/models=${modelsRes.status}, /actuator/health=${healthRes.status}`
    );
  }
  console.log('Smoke: gateway is healthy');
}

export default function () {
  // Test 1: models endpoint (fast, no LLM)
  const modelsRes = http.get(`${GATEWAY_URL}/v1/models`);
  check(modelsRes, {
    'models 200':              r => r.status === 200,
    'models has conduit':     r => r.body.includes('conduit-assistant'),
  });

  // Test 2: chat completions (full pipeline)
  const payload = JSON.stringify({
    model: 'conduit-assistant',
    stream: true,
    messages: [{ role: 'user', content: 'What are the holdings for Whitman Family Office?' }],
  });

  const chatRes = http.post(`${GATEWAY_URL}/v1/chat/completions`, payload, {
    headers: {
      'Content-Type': 'application/json',
      'Accept':       'text/event-stream',
    },
    timeout: '30s',
    responseType: 'text',
  });

  const chatOk = check(chatRes, {
    'chat 200':         r => r.status === 200,
    'chat SSE type':    r => (r.headers['Content-Type'] || '').includes('text/event-stream'),
    'chat has [DONE]':  r => r.body && r.body.includes('[DONE]'),
  });

  errorRate.add(!chatOk);
  sleep(0.5);
}
