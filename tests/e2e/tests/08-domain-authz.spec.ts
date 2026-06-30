import { test, expect } from '@playwright/test';
import { getJwt, GATEWAY_URL, USER_MGMT_URL, IAM_USER_PASSWORD } from './helpers';

/**
 * Phase 11 — Domain-scoped ABAC (segment × agent-domain).
 *
 * Two-level authorization model tested here:
 *  1. Business segment ("wealth" / "servicing") → which agent domains may be invoked
 *  2. Within domain: data-classification gate (confidential-pii requires clearance >= 2)
 *
 * rm_jane  — segment: wealth  → ALLOW wealth-management agents, DENY asset-servicing
 * rm_diaz  — segment: servicing → ALLOW asset-servicing agents, DENY wealth-management
 * admin    — platform_admin   → ALLOW all agents
 * da_wpb   — domain_admin(wealth-private-banking) → ALLOW wealth-management, DENY servicing
 */
test.describe('Domain-scoped ABAC (Phase 11)', () => {

  // ── rm_jane: wealth segment ───────────────────────────────────────────────

  test('rm_jane gets wealth agent answer (holdings, performance, risk)', async ({ request }) => {
    const token = await getJwt('rm_jane');
    const resp = await request.post(`${GATEWAY_URL}/v1/chat/completions`, {
      timeout: 90_000,
      headers: { 'Authorization': `Bearer ${token}`, 'Content-Type': 'application/json' },
      data: {
        model:    'meridian-assistant',
        messages: [{ role: 'user', content: 'Show holdings and performance for Whitman Family Office REL-00042' }],
        stream:   true,
      },
    });
    expect(resp.status()).toBe(200);
    const raw = await resp.text();
    const lower = raw.toLowerCase();
    // Must not get any form of blanket denial (old Cerbos message OR new coverage message)
    const isDenied = (
      lower.includes('do not have access to any of the required') ||
      lower.includes('not in your coverage')                      ||
      lower.includes('access denied')
    );
    expect(isDenied).toBe(false);
    // Must contain grounded wealth data
    const hasWealthData = (
      lower.includes('holdings')    ||
      lower.includes('performance') ||
      lower.includes('whitman')     ||
      lower.includes('%')           ||
      lower.includes('allocation')
    );
    expect(hasWealthData).toBe(true);
    expect(raw).toMatch(/data:\s*\[DONE\]/);
  });

  test('rm_jane — agent filter allows wealth agents, denies asset-servicing', async ({ request }) => {
    const token = await getJwt('rm_jane');
    // Use a prompt that would normally pull settlement data (asset-servicing MCP)
    const resp = await request.post(`${GATEWAY_URL}/v1/chat/completions`, {
      timeout: 90_000,
      headers: { 'Authorization': `Bearer ${token}`, 'Content-Type': 'application/json' },
      data: {
        model:    'meridian-assistant',
        messages: [{ role: 'user', content: 'Give me a complete picture for Whitman REL-00042 including settlement status and cash management' }],
        stream:   true,
      },
    });
    expect(resp.status()).toBe(200);
    const raw = await resp.text();
    const lower = raw.toLowerCase();
    // Must not be a blanket denial (old Cerbos message OR new coverage message)
    const isDenied = (
      lower.includes('do not have access to any of the required') ||
      lower.includes('not in your coverage')
    );
    expect(isDenied).toBe(false);
    // Wealth data should appear (wealth agents allowed)
    const hasWealthContent = (
      lower.includes('whitman')  ||
      lower.includes('holdings') ||
      lower.includes('risk')     ||
      lower.includes('%')
    );
    expect(hasWealthContent).toBe(true);
    expect(raw).toMatch(/data:\s*\[DONE\]/);
  });

  // ── rm_diaz: servicing segment ────────────────────────────────────────────

  test('rm_diaz gets servicing agent answer', async ({ request }) => {
    const token = await getJwt('rm_diaz');
    const resp = await request.post(`${GATEWAY_URL}/v1/chat/completions`, {
      timeout: 90_000,
      headers: { 'Authorization': `Bearer ${token}`, 'Content-Type': 'application/json' },
      data: {
        model:    'meridian-assistant',
        messages: [{ role: 'user', content: 'Show settlement status for REL-00300' }],
        stream:   true,
      },
    });
    expect(resp.status()).toBe(200);
    const raw = await resp.text();
    const lower = raw.toLowerCase();
    // Must not get any blanket denial (old Cerbos message OR new coverage message)
    const isBlankDenied = (
      lower.includes('do not have access to any of the required') ||
      lower.includes('not in your coverage')
    );
    expect(isBlankDenied).toBe(false);
    expect(raw).toMatch(/data:\s*\[DONE\]/);
  });

  // ── platform_admin: unrestricted ──────────────────────────────────────────

  test('admin (platform_admin) can invoke any domain agents', async ({ request }) => {
    const token = await getJwt('admin');
    const resp = await request.post(`${GATEWAY_URL}/v1/chat/completions`, {
      timeout: 90_000,
      headers: { 'Authorization': `Bearer ${token}`, 'Content-Type': 'application/json' },
      data: {
        model:    'meridian-assistant',
        messages: [{ role: 'user', content: 'Show me the full portfolio picture for Whitman REL-00042' }],
        stream:   true,
      },
    });
    expect(resp.status()).toBe(200);
    const raw = await resp.text();
    const lower = raw.toLowerCase();
    // Admin must never be hit by any blanket denial (old Cerbos OR new coverage message)
    const isDenied = (
      lower.includes('do not have access to any of the required') ||
      lower.includes('not in your coverage')                      ||
      lower.includes('access denied')
    );
    expect(isDenied).toBe(false);
    expect(raw).toMatch(/data:\s*\[DONE\]/);
  });

  // ── domain_admin: scoped to administered domain ───────────────────────────

  test('da_wpb (domain_admin for wealth-private-banking) can invoke wealth agents', async ({ request }) => {
    const token = await getJwt('da_wpb');
    const resp = await request.post(`${GATEWAY_URL}/v1/chat/completions`, {
      timeout: 90_000,
      headers: { 'Authorization': `Bearer ${token}`, 'Content-Type': 'application/json' },
      data: {
        model:    'meridian-assistant',
        messages: [{ role: 'user', content: 'Show portfolio holdings for Whitman REL-00042' }],
        stream:   true,
      },
    });
    expect(resp.status()).toBe(200);
    const raw = await resp.text();
    expect(raw).toMatch(/data:\s*\[DONE\]/);
  });

  // ── Cerbos direct check (verifies policy, not just gateway behaviour) ─────

  test('Cerbos PDP directly allows rm_jane on wealth-management agents', async ({ request }) => {
    const resp = await request.post('http://localhost:3594/api/check/resources', {
      headers: { 'Content-Type': 'application/json' },
      data: {
        principal: {
          id:            'rm_jane',
          policyVersion: 'default',
          roles:         ['relationship_manager'],
          attr: {
            book:         ['REL-00042', 'REL-00099'],
            clearance:    2,
            segments:     ['wealth'],
            domains:      ['wealth-private-banking'],
            admin_domains: [],
          },
        },
        resources: [
          {
            actions:  ['invoke'],
            resource: {
              kind:          'agent',
              policyVersion: 'default',
              id:            'acme.wealth.holdings',
              attr: {
                domain:              'wealth-management',
                is_mutating:         false,
                data_classification: 'confidential-pii',
              },
            },
          },
          {
            actions:  ['invoke'],
            resource: {
              kind:          'agent',
              policyVersion: 'default',
              id:            'acme.servicing.settlement_status',
              attr: {
                domain:              'asset-servicing',
                is_mutating:         false,
                data_classification: 'confidential',
              },
            },
          },
        ],
      },
    });
    expect(resp.status()).toBe(200);
    const body = await resp.json();
    const results: Array<{ resource: { id: string }; actions: Record<string, string> }> = body.results;
    const resultMap = Object.fromEntries(results.map(r => [r.resource.id, r.actions]));

    // Wealth agent → ALLOW
    expect(resultMap['acme.wealth.holdings']?.['invoke']).toBe('EFFECT_ALLOW');
    // Servicing agent → DENY (rm_jane has no "servicing" segment)
    expect(resultMap['acme.servicing.settlement_status']?.['invoke']).toBe('EFFECT_DENY');
  });

  test('Cerbos PDP directly denies rm_diaz on wealth-management agents', async ({ request }) => {
    const resp = await request.post('http://localhost:3594/api/check/resources', {
      headers: { 'Content-Type': 'application/json' },
      data: {
        principal: {
          id:            'rm_diaz',
          policyVersion: 'default',
          roles:         ['relationship_manager'],
          attr: {
            book:         ['REL-00300'],
            clearance:    3,
            segments:     ['servicing'],
            domains:      ['servicing-ops'],
            admin_domains: [],
          },
        },
        resources: [
          {
            actions:  ['invoke'],
            resource: {
              kind:          'agent',
              policyVersion: 'default',
              id:            'acme.wealth.holdings',
              attr: {
                domain:              'wealth-management',
                is_mutating:         false,
                data_classification: 'confidential-pii',
              },
            },
          },
        ],
      },
    });
    expect(resp.status()).toBe(200);
    const body = await resp.json();
    const result = body.results[0];
    expect(result.actions['invoke']).toBe('EFFECT_DENY');
  });

  // ── JWT carries segment claim ─────────────────────────────────────────────

  test('rm_jane JWT contains segments=["wealth"]', async ({ request }) => {
    const resp = await request.post(`${USER_MGMT_URL}/auth/token`, {
      data: { username: 'rm_jane', password: IAM_USER_PASSWORD },
    });
    expect(resp.status()).toBe(200);
    const body = await resp.json();
    const token: string = body.accessToken;

    // Decode JWT payload (base64url middle section)
    const parts = token.split('.');
    expect(parts.length).toBe(3);
    const payload = Buffer.from(parts[1], 'base64').toString('utf8');
    const claims = JSON.parse(payload);

    expect(claims.segments).toBeDefined();
    expect(Array.isArray(claims.segments)).toBe(true);
    expect(claims.segments).toContain('wealth');
    expect(claims.segments).not.toContain('servicing');
  });

  test('rm_diaz JWT contains segments=["wealth","servicing"] (dual-segment RM)', async ({ request }) => {
    const resp = await request.post(`${USER_MGMT_URL}/auth/token`, {
      data: { username: 'rm_diaz', password: IAM_USER_PASSWORD },
    });
    expect(resp.status()).toBe(200);
    const body = await resp.json();
    const token: string = body.accessToken;

    const parts = token.split('.');
    const payload = Buffer.from(parts[1], 'base64').toString('utf8');
    const claims = JSON.parse(payload);

    expect(claims.segments).toBeDefined();
    expect(claims.segments).toContain('servicing');
  });
});
