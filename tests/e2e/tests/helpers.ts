import { Page, expect } from '@playwright/test';

// Canonical Conduit chat SPA (Axiom-authenticated BFF). This is the current UI.
export const CHAT_URL       = process.env.CHAT_URL       || 'http://localhost:8099';
export const GATEWAY_URL    = process.env.GATEWAY_URL    || 'http://localhost:8080';
export const USER_MGMT_URL  = process.env.USER_MGMT_URL  || 'http://localhost:8084';
export const GLASSBOX_URL   = process.env.GLASSBOX_URL   || 'http://localhost:4000';

// Legacy LibreChat origin — retained only for specs explicitly about the legacy UI.
// The canonical path is CHAT_URL (:8099). Do not use this for new assertions.
export const LIBRECHAT_URL  = process.env.LIBRECHAT_URL  || 'http://localhost:3080';

// Axiom (IAM) is now the identity provider — there is no self-service registration in the
// chat SPA. Log in as a seeded persona. rm_jane is the default golden-path RM.
export const TEST_USER     = process.env.TEST_USER     || 'rm_jane';
export const TEST_EMAIL    = TEST_USER;                 // back-compat alias (username, not an email)
export const TEST_PASSWORD = process.env.IAM_USER_PASSWORD || 'Meridian@2024';

// IAM service passwords — set via env to support rotated or CI-specific credentials.
// These match the seeds in iam-service/src/main/resources/data.sql (bootstrap defaults).
export const IAM_ADMIN_PASSWORD = process.env.IAM_ADMIN_PASSWORD || 'Meridian@2024';
export const IAM_USER_PASSWORD  = process.env.IAM_USER_PASSWORD  || 'Meridian@2024';

// Hero prompt from agent-catalog.md
export const HERO_PROMPT =
  'Give me a full portfolio review for the Whitman Family Office — ' +
  'I need holdings, YTD performance, risk profile, settlement status, and any pending corporate actions.';

// Okafor relationship — rm_jane should be denied
export const OKAFOR_PROMPT =
  'Show me the complete portfolio details and holdings for the Okafor Family Trust REL-00188';

/** Obtain a real RS256 JWT from the iam-service */
export async function getJwt(userId: string): Promise<string> {
  const resp = await fetch(`${USER_MGMT_URL}/auth/token`, {
    method:  'POST',
    headers: { 'Content-Type': 'application/json' },
    body:    JSON.stringify({ username: userId, password: IAM_USER_PASSWORD }),
  });
  if (!resp.ok) throw new Error(`iam-service /auth/token returned ${resp.status}`);
  const data = await resp.json();
  return data.accessToken as string;
}

/**
 * Log into the canonical chat SPA (:8099) via the Axiom OIDC flow.
 *
 * Flow: the SPA's AuthGate redirects an unauthenticated visitor to `/api/auth/login`
 * → `/oauth2/authorization/conduit-chat` → the Axiom login page at
 * `host.docker.internal:8084/login` (Spring form login, `#username` / `#password`)
 * → OIDC callback → back to `:8099`, session established.
 *
 * Named `registerOrLogin` for back-compat with existing specs; Axiom is the IdP now, so
 * there is no in-app registration — this simply logs in as a seeded persona.
 */
export async function registerOrLogin(
  page: Page,
  username: string = TEST_USER,
  password: string = TEST_PASSWORD,
): Promise<void> {
  await page.goto(`${CHAT_URL}/`, { waitUntil: 'load', timeout: 45_000 });

  // Already authenticated (session reused across tests)? The composer will be present.
  const composer = page.locator('textarea').first();
  if (await composer.isVisible({ timeout: 3_000 }).catch(() => false)) {
    return;
  }

  // Otherwise AuthGate has bounced us to the Axiom login page (on :8084).
  await page.waitForSelector('#username', { state: 'visible', timeout: 30_000 });
  await page.fill('#username', username);
  await page.fill('#password', password);
  await page.click('button[type="submit"], input[type="submit"]');

  // First login for a client may show a Spring Authorization Server consent screen.
  // Approve it if present; otherwise this is a no-op.
  const consent = page.locator('button:has-text("Submit"), button:has-text("Allow"), input[type="submit"]');
  if (await consent.first().isVisible({ timeout: 3_000 }).catch(() => false)) {
    // Only click if we're still on the IAM origin (consent), not already back on the SPA.
    if (page.url().includes(':8084')) {
      await consent.first().click().catch(() => {});
    }
  }

  // Back on the chat SPA with the composer rendered.
  await page.waitForURL(/localhost:8099/, { timeout: 30_000 });
  await composer.waitFor({ state: 'visible', timeout: 30_000 });
}

/** Send a message in the currently open conversation and wait for the reply. */
export async function sendMessage(page: Page, text: string): Promise<string> {
  // Conduit composer: a single <textarea> (placeholder "Ask anything…"); Enter sends.
  const inputBox = page.locator('textarea').first();
  await inputBox.waitFor({ state: 'visible', timeout: 30_000 });

  // Wait for any prior streaming reply to settle (textarea is disabled while streaming).
  await expect(inputBox).toBeEnabled({ timeout: 45_000 }).catch(() => {});

  await inputBox.click();
  await inputBox.fill(text);
  await inputBox.press('Enter');

  return waitForReply(page);
}

/**
 * Wait for the streaming reply to complete and return the full page visible text.
 *
 * The Conduit composer disables its textarea and swaps the Send button for a "Stop" button
 * while streaming. Streaming is done when the "Stop" button is gone and the textarea is
 * enabled again.
 */
export async function waitForReply(page: Page, timeoutMs = 120_000): Promise<string> {
  const inputBox = page.locator('textarea').first();
  try {
    // "Stop" only exists while streaming — wait for it to disappear.
    await page.locator('button:has-text("Stop")').waitFor({ state: 'hidden', timeout: timeoutMs });
    await expect(inputBox).toBeEnabled({ timeout: timeoutMs });
  } catch {
    // Fall through — collect whatever is on the page.
  }

  // Extra buffer for React to flush the last rendered token.
  await page.waitForTimeout(2_000);

  const bodyText = await page.evaluate(() => document.body.innerText).catch(() => '');
  return bodyText;
}

/** Start a fresh conversation (the Conduit sidebar "New chat" navigates to /c/new). */
export async function newConversation(page: Page): Promise<void> {
  await page.goto(`${CHAT_URL}/c/new`, { waitUntil: 'load', timeout: 30_000 }).catch(() => {});
  await page.locator('textarea').first()
      .waitFor({ state: 'visible', timeout: 15_000 }).catch(() => {});
}
