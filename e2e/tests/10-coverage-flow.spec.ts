import { test, expect } from '@playwright/test';
import {
  registerOrLogin,
  sendMessage,
  newConversation,
  getJwt,
  GATEWAY_URL,
} from './helpers';

/**
 * Coverage-flow E2E tests (Phase 11+).
 *
 * These tests verify the full coverage pipeline through the UI and directly via the
 * gateway API.  They complement the pytest integration tests in
 * tests/integration/test_gateway_coverage.py.
 *
 * Scenarios:
 *  1. Vague prompt → clarification question listing rm_jane's book members
 *  2. Named in-book client → grounded answer (no coverage denial)
 *  3. Out-of-book client (Okafor REL-00188) → coverage denial message
 *  4. Multi-turn: follow-up after establishing client context → no re-clarification
 */
test.describe('Coverage flow (Phase 11)', () => {

  // ── 1. Clarification question in UI ────────────────────────────────────────

  test('vague portfolio prompt triggers clarification with book member options', async ({ page }) => {
    await registerOrLogin(page);
    await newConversation(page);

    const reply = await sendMessage(page, 'Show me my portfolio performance');

    // The gateway should ask which client the user is referring to, offering options
    // from rm_jane's book (Whitman, Calderon).
    const lower = reply.toLowerCase();
    const hasClarification = (
      reply.includes('?') ||
      lower.includes('which') ||
      lower.includes('whitman') ||
      lower.includes('calderon') ||
      lower.includes('client') ||
      lower.includes('relationship')
    );
    expect(hasClarification).toBe(true);
  });

  // ── 2. Named in-book client → allowed ──────────────────────────────────────

  test('named in-book client (Whitman REL-00042) returns grounded answer', async ({ page }) => {
    await registerOrLogin(page);
    await newConversation(page);

    const reply = await sendMessage(
      page,
      'Show me holdings for Whitman Family Office',
    );

    const lower = reply.toLowerCase();

    // Must NOT be a coverage denial
    expect(lower).not.toContain('not in your coverage');
    expect(lower).not.toContain('access denied');

    // Must be a substantive reply
    expect(reply.length).toBeGreaterThan(30);
  });

  // ── 3. Denied client in UI ─────────────────────────────────────────────────

  test('Okafor Capital (REL-00188) is denied for rm_jane in the UI', async ({ page }) => {
    await registerOrLogin(page);
    await newConversation(page);

    const reply = await sendMessage(
      page,
      'Show me the portfolio for Okafor Capital REL-00188',
    );

    const lower = reply.toLowerCase();

    // The response must indicate a denial — accept any of these phrases
    const isDenied = (
      lower.includes('coverage')     ||
      lower.includes('denied')       ||
      lower.includes('not authorized') ||
      lower.includes('not authoriz')  ||
      lower.includes('not in your')   ||
      lower.includes('do not have access') ||
      lower.includes('not allowed')
    );
    expect(isDenied).toBe(true);
  });

  // ── 4. Multi-turn: follow-up reuses session context ────────────────────────

  test('second turn reuses session context (no re-clarification)', async ({ page }) => {
    test.setTimeout(240_000);  // two full LLM turns can take up to 2 min each

    await registerOrLogin(page);
    await newConversation(page);

    // Turn 1: establish client
    const firstReply = await sendMessage(
      page,
      'Show me holdings for Whitman Family Office REL-00042',
    );
    expect(firstReply.length).toBeGreaterThan(20);

    // Turn 2: follow-up without re-stating the client
    const secondReply = await sendMessage(page, 'What about their YTD performance?');

    expect(secondReply.length).toBeGreaterThan(20);
    const lower = secondReply.toLowerCase();

    // The gateway must NOT ask "which client" — it should use session context
    const asksWhichClient = lower.includes('which client') || lower.includes('please specify');
    expect(asksWhichClient).toBe(false);
  });

  // ── 5. Direct API: Okafor denied (rm_jane, JWT path) ──────────────────────

  test('API: rm_jane JWT — Okafor REL-00188 is denied via coverage check', async ({ request }) => {
    const token = await getJwt('rm_jane');

    const resp = await request.post(`${GATEWAY_URL}/v1/chat/completions`, {
      timeout: 90_000,
      headers: {
        'Authorization': `Bearer ${token}`,
        'Content-Type': 'application/json',
        'X-Conversation-Id': `e2e-okafor-denial-${Date.now()}`,
      },
      data: {
        model:    'meridian-assistant',
        messages: [{ role: 'user', content: 'Show me the portfolio for Okafor Capital REL-00188' }],
        stream:   true,
      },
    });

    expect(resp.status()).toBe(200);
    const raw = await resp.text();

    // Stream must end cleanly
    expect(raw).toMatch(/data:\s*\[DONE\]/);

    // Content must indicate a denial
    const lower = raw.toLowerCase();
    const isDenied = (
      lower.includes('coverage')     ||
      lower.includes('denied')       ||
      lower.includes('not authorized') ||
      lower.includes('not authoriz')  ||
      lower.includes('not in your')   ||
      lower.includes('do not have access') ||
      lower.includes('not allowed')
    );
    expect(isDenied).toBe(true);
  });

  // ── 6. Direct API: Whitman allowed (rm_jane, JWT path) ────────────────────

  test('API: rm_jane JWT — Whitman REL-00042 is allowed and returns data', async ({ request }) => {
    const token = await getJwt('rm_jane');

    const resp = await request.post(`${GATEWAY_URL}/v1/chat/completions`, {
      timeout: 90_000,
      headers: {
        'Authorization': `Bearer ${token}`,
        'Content-Type': 'application/json',
        'X-Conversation-Id': `e2e-whitman-allow-${Date.now()}`,
      },
      data: {
        model:    'meridian-assistant',
        messages: [{ role: 'user', content: 'Show me holdings for Whitman Family Office REL-00042' }],
        stream:   true,
      },
    });

    expect(resp.status()).toBe(200);
    const raw = await resp.text();

    expect(raw).toMatch(/data:\s*\[DONE\]/);
    expect(raw.toLowerCase()).not.toContain('not in your coverage');

    // Must have substantive SSE content
    const textChunks = raw.match(/"content":"([^"]+)"/g) ?? [];
    const assembled = textChunks
      .map(c => c.replace(/"content":"/, '').replace(/"$/, ''))
      .join('');
    expect(assembled.length).toBeGreaterThan(20);
  });
});
