import { test, expect } from '@playwright/test';
import {
  registerOrLogin,
  newConversation,
  CHAT_URL,
  USER_MGMT_URL,
  TEST_USER,
  TEST_PASSWORD,
} from './helpers';

/**
 * Login flow — canonical Conduit chat SPA (:8099) via the Axiom OIDC identity provider.
 *
 * The SPA's AuthGate redirects an unauthenticated visitor to `/api/auth/login`
 * → `/oauth2/authorization/conduit-chat` → the Axiom login page (Spring form login on
 * :8084, `#username` / `#password`) → OIDC callback → back to :8099.
 *
 * (The legacy LibreChat `#email` / `:3080` login is retired; those assertions moved here.)
 */
test.describe('Login (Axiom OIDC)', () => {

  test('unauthenticated visit redirects to the Axiom login with username + password fields', async ({ page }) => {
    await page.goto(`${CHAT_URL}/`, { waitUntil: 'load', timeout: 45_000 });

    // AuthGate bounces us to the Axiom (IAM) login page.
    await expect(page.locator('#username')).toBeVisible({ timeout: 30_000 });
    await expect(page.locator('#password')).toBeVisible({ timeout: 10_000 });
    await expect(page.locator('button[type="submit"], input[type="submit"]').first()).toBeVisible({ timeout: 10_000 });

    // We are on the IAM origin (:8084), not still on the SPA.
    expect(page.url()).toContain('8084/login');
  });

  test('invalid credentials are rejected and stay on the login page', async ({ page }) => {
    await page.goto(`${CHAT_URL}/`, { waitUntil: 'load', timeout: 45_000 });
    await page.waitForSelector('#username', { state: 'visible', timeout: 30_000 });

    await page.fill('#username', 'nobody');
    await page.fill('#password', 'wrongpassword123');
    await page.click('button[type="submit"], input[type="submit"]');
    await page.waitForTimeout(3_000);

    // Must NOT have reached the chat SPA.
    expect(page.url()).not.toMatch(/localhost:8099\/(c\/|$)/);
    // Spring form login round-trips back to /login (typically ?error).
    expect(page.url()).toContain('8084/login');
  });

  test('valid persona login lands on the chat SPA', async ({ page }) => {
    await registerOrLogin(page, TEST_USER, TEST_PASSWORD);
    expect(page.url()).toContain('localhost:8099');
    await expect(page.locator('textarea').first()).toBeVisible({ timeout: 30_000 });
  });

  test('composer is enabled after login', async ({ page }) => {
    await registerOrLogin(page);
    const textarea = page.locator('textarea').first();
    await expect(textarea).toBeVisible({ timeout: 30_000 });
    await expect(textarea).toBeEnabled();
  });

  test('session persists after page reload', async ({ page }) => {
    await registerOrLogin(page);

    await page.reload({ waitUntil: 'load', timeout: 30_000 });
    await page.waitForTimeout(3_000);

    // Must NOT be bounced back to the Axiom login page.
    expect(page.url()).not.toContain('8084/login');
    await expect(page.locator('textarea').first()).toBeVisible({ timeout: 20_000 });
  });

  test('new conversation starts a fresh chat', async ({ page }) => {
    await registerOrLogin(page);
    await newConversation(page);

    const textarea = page.locator('textarea').first();
    await expect(textarea).toBeVisible({ timeout: 15_000 });
    const value = await textarea.inputValue().catch(() => '');
    expect(value.trim()).toBe('');
  });

  test('typing in the composer is reflected in the textarea', async ({ page }) => {
    await registerOrLogin(page);
    await newConversation(page);

    const textarea = page.locator('textarea').first();
    await textarea.fill('Hello Conduit');
    expect(await textarea.inputValue()).toContain('Hello Conduit');
  });

  test('IAM /auth/token mints a JWT for the persona', async ({ request }) => {
    const resp = await request.post(`${USER_MGMT_URL}/auth/token`, {
      data: { username: TEST_USER, password: TEST_PASSWORD },
    });
    expect(resp.ok()).toBeTruthy();
    const body = await resp.json();
    expect(typeof body.accessToken).toBe('string');
    expect(body.accessToken.length).toBeGreaterThan(20);
  });
});
