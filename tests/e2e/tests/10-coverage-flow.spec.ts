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
 *
 * NOTE on test 3 and 5 (Okafor denial):
 *   RESOLVE is principal-agnostic — it now finds Okafor in the global entity set.
 *   CHECK then denies it (REL-00188 is not in rm_jane's book).  The gateway must
 *   surface a real COVERAGE-DENIAL phrase, not an opaque "not found" message.
 *   "not found" / "could not find" / "unable to find" are deliberately excluded:
 *   those were fallback phrases from when RESOLVE silently filtered by book and
 *   returned empty; they are no longer correct gateway behavior for this path.
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

    // RESOLVE now finds Okafor in the global entity set; CHECK denies it because
    // REL-00188 is not in rm_jane's book.  The response MUST contain a real
    // coverage-denial phrase — opaque "not found" language is no longer correct
    // here and is explicitly excluded from this assertion.
    const isDenied = (
      lower.includes('coverage')           ||
      lower.includes('denied')             ||
      lower.includes('not authorized')     ||
      lower.includes('not authoriz')       ||
      lower.includes('not in your')        ||
      lower.includes('do not have access') ||
      lower.includes('not allowed')
    );
    expect(isDenied).toBe(true);
  });

  // ── 4. Multi-turn: follow-up uses client-sent context ─────────────────────

  test('second turn uses client-sent context (no re-clarification)', async ({ page }) => {
    test.setTimeout(480_000);  // 8 min: login + 2 LLM turns under full-suite load

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

    // The gateway must NOT ask "which client" — it should use the sent message history
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
        model:    'conduit-assistant',
        messages: [{ role: 'user', content: 'Show me the portfolio for Okafor Capital REL-00188' }],
        stream:   true,
      },
    });

    expect(resp.status()).toBe(200);
    const raw = await resp.text();

    // Stream must end cleanly
    expect(raw).toMatch(/data:\s*\[DONE\]/);

    // Assemble content from SSE chunks — each word is a separate delta chunk,
    // so multi-word phrases won't match in the raw SSE text.
    const assembled = raw
      .split('\n')
      .filter(l => l.startsWith('data:') && !l.includes('[DONE]'))
      .map(l => {
        try {
          const parsed = JSON.parse(l.replace(/^data:\s*/, ''));
          return parsed?.choices?.[0]?.delta?.content ?? '';
        } catch { return ''; }
      })
      .join('');
    const lower = assembled.toLowerCase();

    // RESOLVE finds Okafor globally; CHECK denies it for rm_jane.  The gateway
    // must surface a real coverage-denial phrase.  Opaque "not found" language
    // is no longer correct behavior for this path and is excluded below.
    const isDenied = (
      lower.includes('coverage')           ||
      lower.includes('denied')             ||
      lower.includes('not authorized')     ||
      lower.includes('not in your')        ||
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
        model:    'conduit-assistant',
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
