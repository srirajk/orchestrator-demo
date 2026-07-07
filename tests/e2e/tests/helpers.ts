import { Page, expect } from '@playwright/test';

// Canonical Conduit chat SPA (Axiom-authenticated BFF). This is the current UI.
export const CHAT_URL       = process.env.CHAT_URL       || 'http://localhost:8099';
export const GATEWAY_URL    = process.env.GATEWAY_URL    || 'http://localhost:8080';
export const USER_MGMT_URL  = process.env.USER_MGMT_URL  || 'http://localhost:8084';

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

/** The Conduit composer — semantic locator by placeholder ("Ask anything…"). */
export function composer(page: Page) {
  return page.getByPlaceholder(/ask anything/i);
}

/** The glass-box Decision-trace rail — semantic locator by its ARIA landmark name. */
export function tracePanel(page: Page) {
  return page.getByRole('complementary', { name: 'Decision trace' });
}

/** All assistant answer bubbles (keyed off a stable data-testid, not a CSS class). */
export function assistantBubbles(page: Page) {
  return page.getByTestId('assistant-message');
}

/**
 * Log into the canonical chat SPA (:8099) via the Axiom OIDC flow.
 *
 * Flow: the unauthenticated SPA shows a LoginLanding with a "Sign in with SSO" button.
 * Clicking it starts `/oauth2/authorization/conduit-chat` → the Axiom login page at
 * `host.docker.internal:8084/login` (Spring form login) → OIDC callback → back to `:8099`,
 * session established and the composer rendered.
 *
 * Named `registerOrLogin` for back-compat with existing specs; Axiom is the IdP now, so
 * there is no in-app registration — this simply logs in as a seeded persona.
 *
 * Locators are semantic (getByRole / getByLabel / getByPlaceholder) so the flow survives
 * CSS/id churn — the previous `#username` breakage was exactly that class of brittleness.
 */
export async function registerOrLogin(
  page: Page,
  username: string = TEST_USER,
  password: string = TEST_PASSWORD,
): Promise<void> {
  await page.goto(`${CHAT_URL}/`, { waitUntil: 'load', timeout: 45_000 });

  // Already authenticated (session reused across tests)? The composer will be present.
  if (await composer(page).isVisible({ timeout: 3_000 }).catch(() => false)) {
    return;
  }

  // The SPA LoginLanding gates the OIDC flow behind a "Sign in with SSO" button; clicking
  // it redirects to the Axiom (IAM) login page. (Without this click we never leave the SPA
  // — the original bug: the helper waited for the login field on the landing page.)
  const sso = page.getByRole('button', { name: /sign in with sso/i });
  if (await sso.isVisible({ timeout: 8_000 }).catch(() => false)) {
    await sso.click();
  }

  // Axiom login page — Spring form login. Semantic locators via the field <label>s.
  await page.getByLabel('Username').waitFor({ state: 'visible', timeout: 30_000 });
  await page.getByLabel('Username').fill(username);
  await page.getByLabel('Password').fill(password);
  await page.getByRole('button', { name: 'Sign in' }).click();

  // First authorization for a client may show a Spring consent screen. Approve if present
  // and only while still on the IAM origin (never on the SPA).
  const consent = page.getByRole('button', { name: /submit|allow|authorize/i });
  if (page.url().includes(':8084') && await consent.first().isVisible({ timeout: 3_000 }).catch(() => false)) {
    await consent.first().click().catch(() => {});
  }

  // Back on the chat SPA with the composer rendered.
  await page.waitForURL(/localhost:8099/, { timeout: 30_000 });
  await composer(page).waitFor({ state: 'visible', timeout: 30_000 });
}

/** Send a message in the currently open conversation and wait for the reply. */
export async function sendMessage(page: Page, text: string): Promise<string> {
  const inputBox = composer(page);
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
 * While streaming, the composer disables its textarea and swaps Send for a "Stop" button.
 * We first wait for streaming to START (Stop appears) so a slow first token can't make us
 * observe a stale "already hidden" state and return early; then wait for it to finish.
 */
export async function waitForReply(page: Page, timeoutMs = 180_000): Promise<string> {
  const stop = page.getByRole('button', { name: 'Stop' });
  // Streaming started — tolerate a very fast turn where Stop flashes and is gone already.
  await stop.waitFor({ state: 'visible', timeout: 20_000 }).catch(() => {});
  // Streaming finished — Stop gone and the composer re-enabled.
  await stop.waitFor({ state: 'hidden', timeout: timeoutMs }).catch(() => {});
  await expect(composer(page)).toBeEnabled({ timeout: timeoutMs }).catch(() => {});

  // Extra buffer for React to flush the last rendered token.
  await page.waitForTimeout(2_000);

  const bodyText = await page.evaluate(() => document.body.innerText).catch(() => '');
  return bodyText;
}

/** Start a fresh conversation (the Conduit sidebar "New chat" navigates to /c/new). */
export async function newConversation(page: Page): Promise<void> {
  await page.goto(`${CHAT_URL}/c/new`, { waitUntil: 'load', timeout: 30_000 }).catch(() => {});
  await composer(page).waitFor({ state: 'visible', timeout: 15_000 }).catch(() => {});
}
