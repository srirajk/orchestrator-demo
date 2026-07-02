import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './tests',
  fullyParallel: false,          // run sequentially — shared chat session state
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  workers: 1,
  reporter: [['html', { outputFolder: 'playwright-report', open: 'never' }], ['list']],
  outputDir: 'test-results',

  use: {
    baseURL: process.env.CHAT_URL || 'http://localhost:8099',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    headless: true,
    // LibreChat can be slow to stream; allow generous timeouts
    actionTimeout: 30_000,
    navigationTimeout: 30_000,
  },

  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],

  // Deliberately no webServer block — the stack is started externally by docker compose
  // LibreChat browser UI tests involve LLM synthesis (30-90s) + login overhead (~20s).
  // Multi-turn tests need 3+ LLM calls and up to 10 minutes under full-suite load.
  timeout: 600_000,
});
