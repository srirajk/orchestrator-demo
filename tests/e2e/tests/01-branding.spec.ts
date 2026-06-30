import { test, expect } from '@playwright/test';
import { registerOrLogin, LIBRECHAT_URL } from './helpers';

/**
 * Phase 5 / M12 — Conduit branding.
 * The LibreChat UI must present itself as "Conduit AI", not generic LibreChat.
 */
test.describe('Branding', () => {

  test('root page is accessible', async ({ page }) => {
    const resp = await page.goto(LIBRECHAT_URL, { waitUntil: 'domcontentloaded' });
    expect(resp?.status()).toBeLessThan(400);
  });

  test('chat textarea placeholder shows "Conduit AI"', async ({ page }) => {
    await registerOrLogin(page);

    // Wait for the textarea to exist and its placeholder to be populated by React
    await page.waitForFunction(
      () => {
        const el = document.querySelector('#prompt-textarea') as HTMLTextAreaElement | null;
        return el !== null && el.placeholder.length > 0;
      },
      { timeout: 25_000 }
    );

    // React sets placeholder as a DOM property: "Message Conduit AI" — confirmed via live debug
    const placeholder = await page.locator('#prompt-textarea').evaluate(
      el => (el as HTMLTextAreaElement).placeholder
    );
    expect(placeholder).toMatch(/Conduit/i);
  });

  test('model selector is hidden (modelSelect: false)', async ({ page }) => {
    await registerOrLogin(page);

    // The model-select UI must not be visible — locked to Conduit AI in librechat.yaml
    const modelSelect = page.locator('[data-testid="model-select"], button[aria-label*="Model"]').first();
    const visible = await modelSelect.isVisible().catch(() => false);
    expect(visible).toBe(false);
  });
});
