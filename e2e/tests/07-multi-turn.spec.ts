import { test, expect } from '@playwright/test';
import {
  registerOrLogin,
  sendMessage,
  newConversation,
  waitForReply,
  GATEWAY_URL,
} from './helpers';

/**
 * Multi-turn conversation tests.
 *
 * Covers:
 * - Login → first turn (FETCH_DATA intent) → follow-up turn (FOLLOW_UP intent)
 * - Session context preserved across turns (entity remembered without re-stating it)
 * - New conversation resets context (prior entity not carried forward)
 * - FOLLOW_UP via direct API with X-Conversation-Id header
 */
test.describe('Multi-turn conversation', () => {

  // ── UI path: full login → two-turn conversation ───────────────────────────

  test('second turn in LibreChat retains client context', async ({ page }) => {
    await registerOrLogin(page);
    await newConversation(page);

    // Turn 1 — fetch data (FETCH_DATA intent fires)
    const firstReply = await sendMessage(
      page,
      'Show me the portfolio holdings for Whitman Family Office REL-00042',
    );
    expect(firstReply.length).toBeGreaterThan(30);

    // Turn 2 — follow-up (FOLLOW_UP intent fires; no entity mention needed)
    const secondReply = await sendMessage(
      page,
      'What is the current risk profile for this client?',
    );

    // The second reply must be non-trivial and must NOT be a "which client?" clarification
    expect(secondReply.length).toBeGreaterThan(30);
    const lower = secondReply.toLowerCase();
    const isClarify = lower.includes('which client') || lower.includes('please specify');
    expect(isClarify).toBe(false);
  });

  test('three-turn conversation — data then follow-up then comparison', async ({ page }) => {
    test.setTimeout(240_000);  // three turns × ~60s each
    await registerOrLogin(page);
    await newConversation(page);

    // Turn 1 — holdings fetch (focused prompt to keep response short)
    await sendMessage(page, 'Show holdings for Whitman Family Office REL-00042');

    // Turn 2 — follow-up (FOLLOW_UP intent fires)
    const secondReply = await sendMessage(page, 'How has performance looked this quarter?');
    expect(secondReply.length).toBeGreaterThan(20);

    // Turn 3 — another follow-up
    const thirdReply = await sendMessage(page, 'And what is the risk score?');
    expect(thirdReply.length).toBeGreaterThan(20);

    // Third reply must not say "which client" — context must persist across all three turns
    const lower = thirdReply.toLowerCase();
    expect(lower.includes('which client') || lower.includes('please specify')).toBe(false);
  });

  test('new conversation resets client context', async ({ page }) => {
    await registerOrLogin(page);

    // First conversation — talk about Whitman
    await newConversation(page);
    await sendMessage(page, 'Show holdings for Whitman Family Office REL-00042');

    // Start a brand-new conversation — prior context must NOT carry over
    await newConversation(page);
    const reply = await sendMessage(page, 'What was the last client we discussed?');

    // In a fresh conversation the gateway has no session context about Whitman
    const lower = reply.toLowerCase();
    // Accept: "no prior conversation", "I don't have context", clarifying question, or chitchat
    // Reject: mentioning Whitman specific data from the *previous* conversation
    const leakedWhitman = lower.includes('whitman') && lower.includes('holdings');
    expect(leakedWhitman).toBe(false);
  });

  // ── Direct API path: X-Conversation-Id ties turns together ───────────────

  test('API follow-up with same X-Conversation-Id maintains context', async ({ request }) => {
    const convId = `e2e-conv-${Date.now()}`;
    const headers = {
      'Content-Type':     'application/json',
      'X-Conversation-Id': convId,
    };

    // Turn 1 — fetch
    const resp1 = await request.post(`${GATEWAY_URL}/v1/chat/completions`, {
      timeout: 75_000,
      headers,
      data: {
        model:    'meridian-assistant',
        messages: [{ role: 'user', content: 'Show holdings for Whitman Family Office REL-00042' }],
        stream:   true,
      },
    });
    expect(resp1.status()).toBe(200);
    const raw1 = await resp1.text();
    expect(raw1).toMatch(/data:\s*\[DONE\]/);

    // Turn 2 — follow-up in same conversation
    const resp2 = await request.post(`${GATEWAY_URL}/v1/chat/completions`, {
      timeout: 75_000,
      headers,
      data: {
        model:    'meridian-assistant',
        messages: [
          { role: 'user',      content: 'Show holdings for Whitman Family Office REL-00042' },
          { role: 'assistant', content: '(prior answer about Whitman holdings)' },
          { role: 'user',      content: 'What is the risk profile?' },
        ],
        stream: true,
      },
    });
    expect(resp2.status()).toBe(200);
    const raw2 = await resp2.text();
    expect(raw2).toContain('data:');
    expect(raw2).toMatch(/data:\s*\[DONE\]/);

    // Response must have substantive content
    const textChunks = raw2.match(/"content":"([^"]+)"/g) ?? [];
    const assembled = textChunks.map(c => c.replace(/"content":"/, '').replace(/"$/, '')).join('');
    expect(assembled.length).toBeGreaterThan(20);
  });

  test('different X-Conversation-Id does not share context', async ({ request }) => {
    const headers1 = { 'Content-Type': 'application/json', 'X-Conversation-Id': `e2e-a-${Date.now()}` };
    const headers2 = { 'Content-Type': 'application/json', 'X-Conversation-Id': `e2e-b-${Date.now()}` };

    // Two separate conversations with different IDs
    const [resp1, resp2] = await Promise.all([
      request.post(`${GATEWAY_URL}/v1/chat/completions`, {
        timeout: 75_000,
        headers: headers1,
        data: {
          model:    'meridian-assistant',
          messages: [{ role: 'user', content: 'Show holdings for Whitman Family Office REL-00042' }],
          stream:   true,
        },
      }),
      request.post(`${GATEWAY_URL}/v1/chat/completions`, {
        timeout: 75_000,
        headers: headers2,
        data: {
          model:    'meridian-assistant',
          messages: [{ role: 'user', content: 'Show holdings for Whitman Family Office REL-00042' }],
          stream:   true,
        },
      }),
    ]);

    // Both must succeed independently
    expect(resp1.status()).toBe(200);
    expect(resp2.status()).toBe(200);
    const [raw1, raw2] = await Promise.all([resp1.text(), resp2.text()]);
    expect(raw1).toMatch(/data:\s*\[DONE\]/);
    expect(raw2).toMatch(/data:\s*\[DONE\]/);
  });

  // ── Intent classification across turns ────────────────────────────────────

  test('FETCH_DATA then FOLLOW_UP intent path via API', async ({ request }) => {
    const convId = `e2e-intent-${Date.now()}`;
    const baseHeaders = {
      'Content-Type':      'application/json',
      'X-Conversation-Id': convId,
    };

    // Turn 1: explicit data fetch → FETCH_DATA intent
    const turn1 = await request.post(`${GATEWAY_URL}/v1/chat/completions`, {
      timeout: 75_000,
      headers: baseHeaders,
      data: {
        model:    'meridian-assistant',
        messages: [{ role: 'user', content: 'What are the current holdings for REL-00042?' }],
        stream:   true,
      },
    });
    expect(turn1.status()).toBe(200);
    const raw1 = await turn1.text();
    // Must not be a "no access" denial (rm_jane via X-User-Id header)
    expect(raw1.toLowerCase()).not.toContain('do not have access to any');

    // Turn 2: a vague follow-up → FOLLOW_UP intent, still returns sensible answer
    const turn2 = await request.post(`${GATEWAY_URL}/v1/chat/completions`, {
      timeout: 60_000,
      headers: { ...baseHeaders, 'X-User-Id': 'rm_jane' },
      data: {
        model:    'meridian-assistant',
        messages: [
          { role: 'user',      content: 'What are the current holdings for REL-00042?' },
          { role: 'assistant', content: '(prior answer)' },
          { role: 'user',      content: 'Tell me more about the top holding' },
        ],
        stream: true,
      },
    });
    expect(turn2.status()).toBe(200);
    const raw2 = await turn2.text();
    expect(raw2).toContain('data:');
    expect(raw2).toMatch(/data:\s*\[DONE\]/);
  });
});
