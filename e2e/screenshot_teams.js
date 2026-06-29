const { chromium } = require('playwright');
(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage();
  await page.setViewportSize({ width: 1440, height: 900 });

  await page.goto('http://localhost:5180');
  await page.waitForLoadState('networkidle');
  console.log('Current URL:', page.url());

  const emailInput = page.locator('input').first();
  await emailInput.fill('admin');
  const passwordInput = page.locator('input[type="password"]');
  await passwordInput.fill('Meridian@2024');
  await page.locator('button[type="submit"]').click();
  await page.waitForTimeout(3000);
  await page.waitForLoadState('networkidle');
  console.log('After login URL:', page.url());

  await page.screenshot({ path: '/tmp/dashboard_page.png', fullPage: true });
  console.log('Dashboard screenshot saved');

  await page.click('text=Teams');
  await page.waitForTimeout(2500);
  await page.screenshot({ path: '/tmp/teams_page.png', fullPage: true });
  console.log('Teams screenshot saved');

  const auditLink = page.locator('text=Audit').first();
  await auditLink.click();
  await page.waitForTimeout(2500);
  await page.screenshot({ path: '/tmp/audit_log_page.png', fullPage: true });
  console.log('Audit log screenshot saved');

  await browser.close();
})();
