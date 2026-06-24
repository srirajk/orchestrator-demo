import { Page, expect } from '@playwright/test';

export const LIBRECHAT_URL  = process.env.LIBRECHAT_URL  || 'http://localhost:3080';
export const GATEWAY_URL    = process.env.GATEWAY_URL    || 'http://localhost:8080';
export const USER_MGMT_URL  = process.env.USER_MGMT_URL  || 'http://localhost:8084';
export const GLASSBOX_URL   = process.env.GLASSBOX_URL   || 'http://localhost:4000';

export const TEST_EMAIL    = 'meridian.e2e@test.local';
export const TEST_PASSWORD = 'Meridian@E2E#2025!';

// Hero prompt from agent-catalog.md
export const HERO_PROMPT =
  'Give me a full portfolio review for the Whitman Family Office — ' +
  'I need holdings, YTD performance, risk profile, settlement status, and any pending corporate actions.';

// Okafor relationship — rm_jane should be denied
export const OKAFOR_PROMPT =
  'Show me the complete portfolio details and holdings for the Okafor Family Trust REL-00188';

/** Obtain a real RS256 JWT from the user-mgmt service */
export async function getJwt(userId: string): Promise<string> {
  const resp = await fetch(`${USER_MGMT_URL}/auth/token`, {
    method:  'POST',
    headers: { 'Content-Type': 'application/json' },
    body:    JSON.stringify({ user_id: userId }),
  });
  if (!resp.ok) throw new Error(`user-mgmt /auth/token returned ${resp.status}`);
  const data = await resp.json();
  return data.access_token as string;
}

/** Register then login, or just login if account already exists. */
export async function registerOrLogin(page: Page): Promise<void> {
  // LibreChat is a React SPA — use 'load' not 'networkidle' (background sockets never go idle)
  await page.goto('/login', { waitUntil: 'load', timeout: 45_000 });

  // Wait for React to render the login form
  await page.waitForSelector('#email', { state: 'visible', timeout: 25_000 });

  await page.fill('#email', TEST_EMAIL);
  await page.fill('#password', TEST_PASSWORD);
  await page.click('button[type="submit"]');

  // After a successful login LibreChat redirects to /c/new
  try {
    await page.waitForURL('**/c/**', { timeout: 12_000 });
    return;
  } catch {
    // Login failed — account may not exist yet, try to register
  }

  await page.goto('/register', { waitUntil: 'load', timeout: 45_000 });
  await page.waitForSelector('#name, input[name="name"]', { state: 'visible', timeout: 20_000 });

  await page.fill('#name', 'Meridian E2E Tester').catch(async () => {
    await page.fill('input[name="name"]', 'Meridian E2E Tester');
  });
  await page.fill('#email', TEST_EMAIL);
  await page.fill('#password', TEST_PASSWORD);

  // LibreChat register form may have a confirm_password field
  const confirmSel = '#confirm_password, #confirmPassword, input[name="confirm_password"]';
  const confirmEl = page.locator(confirmSel).first();
  if (await confirmEl.isVisible({ timeout: 3_000 }).catch(() => false)) {
    await confirmEl.fill(TEST_PASSWORD);
  }

  await page.click('button[type="submit"]');
  await page.waitForURL(/\/(c\/|login)/, { timeout: 20_000 });
}

/** Send a message in the currently open conversation and wait for the reply. */
export async function sendMessage(page: Page, text: string): Promise<string> {
  // Confirmed selector from live inspection: #prompt-textarea (data-testid="text-input")
  const inputBox = page.locator('#prompt-textarea').first();
  await inputBox.waitFor({ state: 'visible', timeout: 30_000 });
  await inputBox.click();
  await inputBox.fill(text);

  // Send — #send-button confirmed from live inspection
  await page.click('#send-button');

  return waitForReply(page);
}

/** Wait for the streaming reply to complete and return the full page visible text.
 *
 * LibreChat v0.8.x uses aria-label="Message N" containers (not data-message-author-role).
 * The most reliable signal is: #send-button is re-enabled after streaming ends.
 * We then return the full body innerText so callers can search it for their expected content.
 */
export async function waitForReply(page: Page, timeoutMs = 75_000): Promise<string> {
  const deadline = Date.now() + timeoutMs;

  // Wait until the send button is no longer disabled.
  // LibreChat sets disabled="" on #send-button while streaming.
  try {
    await expect(page.locator('#send-button')).toBeEnabled({ timeout: timeoutMs });
  } catch {
    // If the button check times out, fall through — collect whatever is on the page
  }

  // Extra buffer for React to flush the last rendered token
  await page.waitForTimeout(2_000);

  // Return the full visible text of the page; callers search within it.
  // This is robust across LibreChat versions and doesn't depend on internal selectors.
  const bodyText = await page.evaluate(() => document.body.innerText).catch(() => '');
  return bodyText;
}

/** Click the "New chat" button to start a fresh conversation. */
export async function newConversation(page: Page): Promise<void> {
  // Confirmed selector from live inspection: button[aria-label="New chat"]
  const btn = page.locator('button[aria-label="New chat"]').first();
  if (await btn.isVisible({ timeout: 5_000 }).catch(() => false)) {
    await btn.click();
    await page.waitForTimeout(1_500);
  }
}
