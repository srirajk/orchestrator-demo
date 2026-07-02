import { test, expect } from '@playwright/test';
import { registerOrLogin, CHAT_URL } from './helpers';

/**
 * Conduit branding — the canonical chat SPA (:8099) must present itself as "Conduit".
 * (Replaces the legacy LibreChat placeholder-branding checks on :3080.)
 */
test.describe('Branding (Conduit chat SPA)', () => {

  test('root page is reachable', async ({ page }) => {
    const resp = await page.goto(`${CHAT_URL}/`, { waitUntil: 'domcontentloaded' });
    // AuthGate may 302 to the IAM login, but the initial document must load without a server error.
    expect((resp?.status() ?? 200)).toBeLessThan(400);
  });

  test('document title is "Conduit"', async ({ page }) => {
    await registerOrLogin(page);
    await expect(page).toHaveTitle(/Conduit/i, { timeout: 15_000 });
  });

  test('app chrome shows the Conduit brand', async ({ page }) => {
    await registerOrLogin(page);
    // The sidebar renders the Conduit wordmark once authenticated.
    await expect(page.getByText(/Conduit/i).first()).toBeVisible({ timeout: 20_000 });
  });
});
