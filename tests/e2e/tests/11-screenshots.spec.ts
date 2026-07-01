import { test, expect } from '@playwright/test';
import { registerOrLogin, newConversation, sendMessage, HERO_PROMPT } from './helpers';
import path from 'node:path';
import fs from 'node:fs';

/**
 * Capture utility (and a smoke test): logs in, screenshots the branded home, sends the
 * hero prompt, and screenshots the grounded answer. The PNGs land in docs/images/ and are
 * embedded in README.md — so the README screenshots are reproducible, not hand-pasted.
 * Run just this: `npx playwright test 11-screenshots`.
 */
const imgDir = path.resolve(__dirname, '../../../docs/images');

test.describe('README screenshots', () => {
  test('branded home + grounded hero answer', async ({ page }) => {
    fs.mkdirSync(imgDir, { recursive: true });

    await registerOrLogin(page);
    await newConversation(page);
    await page.waitForTimeout(1200);
    await page.screenshot({ path: path.join(imgDir, 'librechat-home.png') });

    const reply = await sendMessage(page, HERO_PROMPT);
    expect(reply.length).toBeGreaterThan(50);

    await page.waitForTimeout(1000);
    await page.mouse.wheel(0, -4000);   // scroll to the top of the conversation for a clean shot
    await page.waitForTimeout(600);
    await page.screenshot({ path: path.join(imgDir, 'librechat-answer.png') });
  });
});
