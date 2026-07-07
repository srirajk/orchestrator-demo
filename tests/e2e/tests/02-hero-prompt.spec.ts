import { test, expect } from '@playwright/test';
import {
  registerOrLogin,
  sendMessage,
  newConversation,
  assistantBubbles,
  tracePanel,
  HERO_PROMPT,
  GATEWAY_URL,
} from './helpers';

/**
 * Phase 4 / M6-M7 — End-to-end hero prompt through the Conduit Chat SPA (:8099).
 * The hero prompt must fan out across HTTP + MCP agents and return a grounded, synthesised
 * answer in the chat pane, with the built-in glass-box Decision-trace panel populated live.
 */
test.describe('Hero prompt', () => {

  test('streams a grounded answer in the Conduit Chat pane with a live Decision trace', async ({ page }) => {
    await registerOrLogin(page);
    await newConversation(page);

    const reply = await sendMessage(page, HERO_PROMPT);

    // The answer renders as an assistant bubble in the chat pane.
    await expect(assistantBubbles(page).last()).toBeVisible();
    expect((await assistantBubbles(page).last().innerText()).length).toBeGreaterThan(50);
    expect(reply.length).toBeGreaterThan(50);

    // The glass-box Decision trace populates: intent classification + agent resolution.
    const trace = tracePanel(page);
    await expect(trace.getByText(/Intent:/i)).toBeVisible();
    await expect(trace.getByText(/Resolved \d+ Agent/i).first()).toBeVisible();
  });

  test('reply contains grounded facts and the trace shows segment + classification gates', async ({ page }) => {
    await registerOrLogin(page);
    await newConversation(page);

    const reply = await sendMessage(page, HERO_PROMPT);

    // The wealth/servicing agents return Whitman Family Office data; the answer must be grounded
    // in facts actually returned by the mock agents (case-insensitive).
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

    // The glass-box shows the authorization gates that admitted the fan-out.
    const trace = tracePanel(page);
    await expect(trace.getByText(/Segment Passed/i).first()).toBeVisible();
    await expect(trace.getByText(/Classification Passed/i).first()).toBeVisible();
  });

  test('gateway /v1/models returns model list', async ({ request }) => {
    const resp = await request.get(`${GATEWAY_URL}/v1/models`);
    expect(resp.status()).toBe(200);
    const body = await resp.json();
    const ids: string[] = body.data?.map((m: { id: string }) => m.id) ?? [];
    expect(ids).toContain('conduit-assistant');
  });

  test('streaming SSE is well-formed (ends with [DONE])', async ({ request }) => {
    const resp = await request.post(`${GATEWAY_URL}/v1/chat/completions`, {
      headers: { 'Content-Type': 'application/json' },
      data: {
        model:    'conduit-assistant',
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
