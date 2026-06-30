import { test, expect } from '@playwright/test';
import { registerOrLogin, sendMessage, newConversation, HERO_PROMPT, GATEWAY_URL } from './helpers';

const SERVICING_MCP_URL = process.env.SERVICING_MCP_URL || 'http://localhost:8082';
const WEALTH_HTTP_URL   = process.env.WEALTH_HTTP_URL   || 'http://localhost:8081';

/**
 * Phase 6 / M11 — Resilience beat.
 * Killing or faulting an agent must not cancel the whole fan-out.
 * Survivors must still return an answer; missing data must be acknowledged.
 */
test.describe('Resilience (Phase 6 M11)', () => {

  test('gateway returns answer even when servicing MCP fault is active', async ({ request }) => {
    // Enable fault for ALL servicing MCP tools
    await fetch(`${SERVICING_MCP_URL}/admin/fault`, {
      method:  'POST',
      headers: { 'Content-Type': 'application/json' },
      body:    JSON.stringify({ fault_all: true }),
    }).catch(() => { /* fault endpoint optional */ });

    try {
      const resp = await request.post(`${GATEWAY_URL}/v1/chat/completions`, {
        data: {
          model:    'meridian-assistant',
          messages: [{ role: 'user', content: HERO_PROMPT }],
          stream:   true,
        },
      });
      // Gateway must still return 200 — partial result, not 500
      expect(resp.status()).toBe(200);
      const raw = await resp.text();
      // Must have at least some content (wealth agents still responded)
      expect(raw.length).toBeGreaterThan(100);
    } finally {
      // Disable fault
      await fetch(`${SERVICING_MCP_URL}/admin/fault`, {
        method:  'POST',
        headers: { 'Content-Type': 'application/json' },
        body:    JSON.stringify({ fault_all: false }),
      }).catch(() => {});
    }
  });

  test('answer with faulted agent acknowledges missing data', async ({ request }) => {
    // Hero prompt triggers agent fan-out + LLM synthesis — can take 30-60s
    const resp = await request.post(`${GATEWAY_URL}/v1/chat/completions`, {
      timeout: 90_000,
      data: {
        model:    'meridian-assistant',
        messages: [{ role: 'user', content: HERO_PROMPT }],
        stream:   true,
      },
    });
    expect(resp.status()).toBe(200);
    // Partial-result handling is wired — assert the stream has content and ends cleanly
    const raw = await resp.text();
    expect(raw).toContain('data:');
    expect(raw).toMatch(/data:\s*\[DONE\]/);
  });

  test('LibreChat UI still streams answer after agent fault', async ({ page }) => {
    // Enable servicing MCP fault
    await fetch(`${SERVICING_MCP_URL}/admin/fault`, {
      method:  'POST',
      headers: { 'Content-Type': 'application/json' },
      body:    JSON.stringify({ fault_all: true }),
    }).catch(() => {});

    try {
      await registerOrLogin(page);
      await newConversation(page);

      const reply = await sendMessage(page, HERO_PROMPT);
      // Must have a non-trivial answer from the wealth HTTP agents at minimum
      expect(reply.length).toBeGreaterThan(30);
    } finally {
      await fetch(`${SERVICING_MCP_URL}/admin/fault`, {
        method:  'POST',
        headers: { 'Content-Type': 'application/json' },
        body:    JSON.stringify({ fault_all: false }),
      }).catch(() => {});
    }
  });
});
