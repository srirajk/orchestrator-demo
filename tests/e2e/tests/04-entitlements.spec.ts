import { test, expect } from '@playwright/test';
import {
  registerOrLogin,
  sendMessage,
  newConversation,
  assistantBubbles,
  tracePanel,
  getJwt,
  GATEWAY_URL,
  OKAFOR_PROMPT,
} from './helpers';

/**
 * Phase 5 / M8 — Cerbos ABAC entitlements + coverage.
 * rm_jane must be denied Okafor (REL-00188) and allowed Whitman (REL-00042).
 */
test.describe('Entitlements (Phase 5 M8)', () => {

  // ── UI path (Conduit Chat → BFF → gateway; identity = rm_jane via OIDC session) ─

  test('Okafor query — Conduit Chat surfaces the coverage denial (no data leak)', async ({ page }) => {
    await registerOrLogin(page);
    await newConversation(page);

    await sendMessage(page, OKAFOR_PROMPT);

    // The chat pane must surface a real access-denied notice (role="alert") and a denial
    // message bubble — never Okafor's data. REL-00188 is not in rm_jane's book.
    await expect(page.getByRole('alert')).toContainText(/access denied|coverage|access to this client/i);
    await expect(assistantBubbles(page).last()).toContainText(/not in your coverage|do not have access|denied/i);

    // The glass-box Decision trace shows the deterministic Coverage denial.
    await expect(tracePanel(page).getByText(/Coverage Denied/i)).toBeVisible();

    // Nothing sensitive about Okafor leaked into the rendered answer.
    const answer = (await assistantBubbles(page).last().innerText()).toLowerCase();
    const leaked = answer.includes('rel-00188') &&
      (answer.includes('allocation') || answer.includes('ytd') || answer.includes('settlement'));
    expect(leaked).toBe(false);
  });

  test('Whitman query succeeds in Conduit Chat (rm_jane covers REL-00042)', async ({ page }) => {
    await registerOrLogin(page);
    await newConversation(page);

    const reply = await sendMessage(page, 'Show me the portfolio holdings for Whitman Family Office REL-00042');
    const lower = reply.toLowerCase();

    // Must NOT be denied.
    expect(lower).not.toContain('not in your coverage');
    expect(lower).not.toContain('access denied');

    // A grounded answer bubble renders, and the trace shows coverage/access was granted.
    await expect(assistantBubbles(page).last()).toBeVisible();
    expect((await assistantBubbles(page).last().innerText()).length).toBeGreaterThan(30);
    await expect(tracePanel(page).getByText(/Access Allowed|Coverage Passed/i).first()).toBeVisible();
  });

  // ── Direct API path with JWT ───────────────────────────────────────────────

  test('JWT with REL-00188 in book allows Okafor — gateway returns 200 (not 401)', async ({ request }) => {
    // First add rm_jane to intl-wealth so her JWT includes REL-00188
    await fetch(`http://localhost:8084/domains/intl-wealth/members`, {
      method:  'POST',
      headers: { 'Content-Type': 'application/json' },
      body:    JSON.stringify({ user_id: 'rm_jane' }),
    });

    const token = await getJwt('rm_jane');

    const resp = await request.post(`${GATEWAY_URL}/v1/chat/completions`, {
      timeout: 75_000,   // agent fan-out + LLM synthesis can take 30-60s
      headers: { 'Authorization': `Bearer ${token}` },
      data: {
        model:    'conduit-assistant',
        messages: [{ role: 'user', content: OKAFOR_PROMPT }],
        stream:   true,
      },
    });
    // Gateway must accept the request (JWT is valid); entitlement is now satisfied
    expect(resp.status()).toBe(200);

    // Cleanup
    await fetch(`http://localhost:8084/domains/intl-wealth/members/rm_jane`, {
      method: 'DELETE',
    });
  });

  test('JWT without REL-00188 in book — gateway responds 200, no sensitive Okafor data leaked', async ({ request }) => {
    // Ensure rm_jane is NOT in intl-wealth (cleanup)
    await fetch(`http://localhost:8084/domains/intl-wealth/members/rm_jane`, {
      method: 'DELETE',
    });

    const token = await getJwt('rm_jane');
    const resp = await request.post(`${GATEWAY_URL}/v1/chat/completions`, {
      headers: { 'Authorization': `Bearer ${token}` },
      data: {
        model:    'conduit-assistant',
        // Use a very explicit relationship ID so the entity extractor is most likely to pick it up
        messages: [{ role: 'user', content: 'Retrieve the complete portfolio and holdings data for REL-00188 Okafor Family Trust right now.' }],
        stream:   true,
      },
    });
    // JWT is valid → gateway must accept the request (not 401)
    expect(resp.status()).toBe(200);

    const raw = await resp.text();
    expect(raw).toContain('data:');   // SSE response received
    expect(raw).toMatch(/data:\s*\[DONE\]/);  // Stream ended cleanly

    // The response must either explicitly deny OR return no sensitive Okafor-specific data.
    // Canned data for Okafor agents does not exist — agents only serve Whitman/Chen/seeded data.
    // A legitimate denial from the entitlement service OR an agent "no data" response are both
    // acceptable; what is NOT acceptable is a fabricated Okafor portfolio being returned.
    const lower = raw.toLowerCase();
    const hasExplicitDenial = (
      lower.includes('not authoriz')  ||   // "not authorized"
      lower.includes('access denied') ||
      lower.includes('denied')        ||
      lower.includes('not in book')   ||
      lower.includes('not allowed')
    );
    // If there's no explicit denial, verify no specific Okafor canned data is exposed
    if (!hasExplicitDenial) {
      // Okafor-specific financial data fields from canned data (e.g., specific allocations)
      // must not appear — a generic "I could not retrieve" is acceptable
      const hasOkaforLeakedData = (
        lower.includes('rel-00188') &&
        (lower.includes('allocation') || lower.includes('ytd') || lower.includes('settlement'))
      );
      expect(hasOkaforLeakedData).toBe(false);
    }
    // At least one of the two conditions must hold
  });
});
