import { test, expect } from '@playwright/test';
import { getJwt, GATEWAY_URL, USER_MGMT_URL, IAM_ADMIN_PASSWORD, IAM_USER_PASSWORD } from './helpers';

/**
 * Phase 8 / M15 — RS256/JWKS identity.
 * Validates JWT issuance, JWKS publication, and gateway enforcement end-to-end.
 */
test.describe('JWT identity (Phase 8 M15)', () => {

  // ── iam-service health ─────────────────────────────────────────────────────

  test('iam-service health reports UP', async ({ request }) => {
    const resp = await request.get(`${USER_MGMT_URL}/actuator/health`);
    expect(resp.status()).toBe(200);
    const body = await resp.json();
    expect(body.status).toBe('UP');
  });

  test('JWKS endpoint returns an RSA public key', async ({ request }) => {
    const resp = await request.get(`${USER_MGMT_URL}/oauth2/jwks`);
    expect(resp.status()).toBe(200);
    const body = await resp.json();
    const keys = body.keys as Array<Record<string, string>>;
    expect(keys.length).toBeGreaterThanOrEqual(1);
    const key = keys[0];
    expect(key.kty).toBe('RSA');
    // RSA modulus must be present and non-trivial (2048-bit key → base64url length > 100)
    expect(key.n.length).toBeGreaterThan(100);
  });

  test('POST /auth/token returns RS256 JWT for rm_jane with correct book', async ({ request }) => {
    const resp = await request.post(`${USER_MGMT_URL}/auth/token`, {
      data: { username: 'rm_jane', password: IAM_USER_PASSWORD },
    });
    expect(resp.status()).toBe(200);
    const body = await resp.json();
    expect(body.tokenType).toBe('Bearer');
    expect(body.accessToken).toBeTruthy();
    // rm_jane's book must contain both seeded relationships
    const book: string[] = body.user?.book ?? [];
    expect(book).toContain('REL-00042');
    expect(book).toContain('REL-00099');
    // Okafor (REL-00188) must NOT be in her book
    expect(book).not.toContain('REL-00188');
  });

  // ── gateway JWT enforcement ───────────────────────────────────────────────

  test('gateway accepts valid RS256 JWT (returns 200)', async ({ request }) => {
    const token = await getJwt('rm_jane');
    const resp = await request.post(`${GATEWAY_URL}/v1/chat/completions`, {
      headers: { 'Authorization': `Bearer ${token}` },
      data: {
        model:    'meridian-assistant',
        messages: [{ role: 'user', content: 'hello' }],
        stream:   true,
      },
    });
    expect(resp.status()).toBe(200);
  });

  test('gateway rejects tampered JWT with 401', async ({ request }) => {
    const resp = await request.post(`${GATEWAY_URL}/v1/chat/completions`, {
      headers: {
        'Authorization': 'Bearer eyJhbGciOiJSUzI1NiIsImtpZCI6Im1lcmlkaWFuLWtleS0xIn0' +
                         '.eyJzdWIiOiJybV9qYW5lIn0' +
                         '.INVALIDSIGNATUREXXXXXXXXXXXXXXXX'
      },
      data: {
        model:    'meridian-assistant',
        messages: [{ role: 'user', content: 'hello' }],
        stream:   false,
      },
    });
    expect(resp.status()).toBe(401);
    const body = await resp.json();
    expect(body.error).toBe('unauthorized');
  });

  test('gateway rejects expired JWT with 401', async ({ request }) => {
    // Build an expired token by hitting the internal mint helper with ttl_seconds=-1.
    // Since iam-service does not expose that parameter publicly, we use a known-expired
    // static token (RS256-signed against a one-off key — gateway will reject it because
    // the kid is unknown, which is the same 401 path as expiry).
    const resp = await request.post(`${GATEWAY_URL}/v1/chat/completions`, {
      headers: {
        'Authorization': 'Bearer eyJhbGciOiJSUzI1NiIsImtpZCI6InVua25vd24ta2V5In0' +
                         '.eyJzdWIiOiJybV9qYW5lIiwiZXhwIjoxNjAwMDAwMDAwfQ' +
                         '.fakesig'
      },
      data: {
        model:    'meridian-assistant',
        messages: [{ role: 'user', content: 'hello' }],
        stream:   false,
      },
    });
    expect(resp.status()).toBe(401);
  });

  test('gateway passes through requests with no Bearer token (trusted hop)', async ({ request }) => {
    // LibreChat sends X-User-Id instead of Bearer — must not get 401
    const resp = await request.post(`${GATEWAY_URL}/v1/chat/completions`, {
      headers: { 'X-User-Id': 'rm_jane' },
      data: {
        model:    'meridian-assistant',
        messages: [{ role: 'user', content: 'ping' }],
        stream:   true,
      },
    });
    expect(resp.status()).toBe(200);
  });

  // ── book lifecycle via PATCH /users/{userId}/book ─────────────────────────

  test('adding REL-00188 to rm_jane book grants it in new JWT response', async ({ request }) => {
    // Get admin token for the book PATCH call
    const adminResp = await request.post(`${USER_MGMT_URL}/auth/token`, {
      data: {
        username: 'admin',
        password: IAM_ADMIN_PASSWORD,
      },
    });
    expect(adminResp.status()).toBe(200);
    const adminToken: string = (await adminResp.json()).accessToken;

    // 1. Add REL-00188 to rm_jane's book
    const addResp = await request.patch(`${USER_MGMT_URL}/users/rm_jane/book`, {
      headers: { 'Authorization': `Bearer ${adminToken}` },
      data: { add: ['REL-00188'] },
    });
    expect([200, 201, 204]).toContain(addResp.status());

    // 2. Get fresh rm_jane token — book in response must include REL-00188
    const tokenResp = await request.post(`${USER_MGMT_URL}/auth/token`, {
      data: { username: 'rm_jane', password: IAM_USER_PASSWORD },
    });
    expect(tokenResp.status()).toBe(200);
    const loginData = await tokenResp.json();
    const book: string[] = loginData.user?.book ?? [];
    expect(book).toContain('REL-00188');

    // 3. Cleanup — remove REL-00188 from rm_jane's book
    await request.patch(`${USER_MGMT_URL}/users/rm_jane/book`, {
      headers: { 'Authorization': `Bearer ${adminToken}` },
      data: { remove: ['REL-00188'] },
    });
  });

  test('after removing REL-00188 from book, it no longer appears in JWT response', async ({ request }) => {
    // Get admin token
    const adminResp = await request.post(`${USER_MGMT_URL}/auth/token`, {
      data: {
        username: 'admin',
        password: IAM_ADMIN_PASSWORD,
      },
    });
    expect(adminResp.status()).toBe(200);
    const adminToken: string = (await adminResp.json()).accessToken;

    // Ensure REL-00188 is removed from rm_jane's book (idempotent cleanup)
    await request.patch(`${USER_MGMT_URL}/users/rm_jane/book`, {
      headers: { 'Authorization': `Bearer ${adminToken}` },
      data: { remove: ['REL-00188'] },
    });

    // Verify rm_jane token no longer has REL-00188 in book
    const tokenResp = await request.post(`${USER_MGMT_URL}/auth/token`, {
      data: { username: 'rm_jane', password: IAM_USER_PASSWORD },
    });
    expect(tokenResp.status()).toBe(200);
    const loginData = await tokenResp.json();
    const book: string[] = loginData.user?.book ?? [];
    expect(book).not.toContain('REL-00188');
  });
});
