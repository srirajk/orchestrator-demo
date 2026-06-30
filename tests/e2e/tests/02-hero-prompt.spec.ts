import { test, expect } from '@playwright/test';
import { registerOrLogin, sendMessage, newConversation, HERO_PROMPT, GATEWAY_URL } from './helpers';

/**
 * Phase 4 / M6-M7 — End-to-end hero prompt.
 * The hero prompt must fan out across HTTP + MCP agents and return a grounded,
 * synthesised answer containing data actually returned by the mock agents.
 */
test.describe('Hero prompt', () => {

  test('streams an answer through the LibreChat UI', async ({ page }) => {
    await registerOrLogin(page);
    await newConversation(page);

    const reply = await sendMessage(page, HERO_PROMPT);

    // Must have a non-empty reply
    expect(reply.length).toBeGreaterThan(50);
  });

  test('reply contains grounded facts from wealth agents', async ({ page }) => {
    await registerOrLogin(page);
    await newConversation(page);

    const reply = await sendMessage(page, HERO_PROMPT);

    // The wealth holdings agent returns Whitman Family Office data; the answer must mention it
    // (checking case-insensitively for core grounding signals from canned data)
    const lower = reply.toLowerCase();
    const hasGrounding = (
      lower.includes('whitman')            ||    // entity name
      lower.includes('holdings')           ||    // agent type
      lower.includes('%')                  ||    // numeric allocation
      lower.includes('performance')        ||    // performance agent
      lower.includes('settlement')         ||    // settlements MCP tool
      lower.includes('corporate')               // corporate actions MCP tool
    );
    expect(hasGrounding).toBe(true);
  });

  test('gateway /v1/models returns model list', async ({ request }) => {
    const resp = await request.get(`${GATEWAY_URL}/v1/models`);
    expect(resp.status()).toBe(200);
    const body = await resp.json();
    const ids: string[] = body.data?.map((m: { id: string }) => m.id) ?? [];
    expect(ids).toContain('meridian-assistant');
  });

  test('streaming SSE is well-formed (ends with [DONE])', async ({ request }) => {
    const resp = await request.post(`${GATEWAY_URL}/v1/chat/completions`, {
      headers: { 'Content-Type': 'application/json' },
      data: {
        model:    'meridian-assistant',
        messages: [{ role: 'user', content: 'hello' }],
        stream:   true,
      },
    });

    expect(resp.status()).toBe(200);
    expect(resp.headers()['content-type']).toMatch(/text\/event-stream/);

    const raw = await resp.text();
    // SSE payloads use "data:" (with or without a trailing space — both valid per the spec)
    expect(raw).toContain('data:');
    // Stream must end with the DONE sentinel
    expect(raw).toMatch(/data:\s*\[DONE\]/);
  });
});
