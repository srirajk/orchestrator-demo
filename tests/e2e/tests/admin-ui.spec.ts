import { test, expect, Page } from '@playwright/test'
import { IAM_ADMIN_PASSWORD, IAM_USER_PASSWORD } from './helpers'

const BASE = process.env.ADMIN_UI_URL || 'http://localhost:5180'

// Collect uncaught page errors AND route-crash console logs for the whole test. The admin pages
// crashed with `TypeError: ...segments.map is not a function` only AFTER react-query resolved — so
// the old "assert no error text immediately" checks passed as a race (false green). These listeners
// catch the real thing whenever it fires.
function watchForCrashes(page: Page): string[] {
  const errors: string[] = []
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`))
  page.on('console', (m) => {
    if (m.type() === 'error' && /route crashed|is not a function/i.test(m.text())) {
      errors.push(`console: ${m.text()}`)
    }
  })
  return errors
}

async function loginAdmin(page: Page) {
  await page.goto(`${BASE}/login`)
  await page.getByLabel(/username/i).fill('admin')
  await page.getByLabel(/password/i).fill(IAM_ADMIN_PASSWORD)
  await page.getByRole('button', { name: /sign in/i }).click()
  await page.waitForURL(`${BASE}/`, { timeout: 8000 })
}

// The crash fires only AFTER react-query resolves and the table renders — so wait for the network
// to settle and give React a beat to render (or crash) BEFORE asserting. Without this the checks
// race the data load and pass on a page that is about to crash (the original false green).
async function assertNoCrash(page: Page, errors: string[]) {
  // NOT networkidle — the Workbench holds a live SSE/trace connection that never goes idle. A fixed
  // settle is enough for react-query to resolve and the table to render (or crash).
  await page.waitForTimeout(3000)
  await expect(page.getByText(/this view could not render/i)).toHaveCount(0)
  expect(errors, `page threw: ${errors.join(' | ')}`).toEqual([])
}

test.describe('Admin UI', () => {
  test('login page renders', async ({ page }) => {
    await page.goto(BASE)
    await expect(page).toHaveURL(/login/)
  })

  test('login with admin credentials → dashboard', async ({ page }) => {
    await loginAdmin(page)
    await expect(page.getByText(/users/i).first()).toBeVisible()
  })

  // ── The auth-integrity fix: a non-admin (chat_user) must NOT reach the console ──────────────────
  test('rm_jane (chat_user) is rejected from the admin console', async ({ page }) => {
    await page.goto(`${BASE}/login`)
    await page.getByLabel(/username/i).fill('rm_jane')
    await page.getByLabel(/password/i).fill(IAM_USER_PASSWORD)
    await page.getByRole('button', { name: /sign in/i }).click()
    // Must stay on /login with an authorization error — never reach the dashboard.
    await expect(page.getByText(/not authorized for the admin console/i)).toBeVisible({ timeout: 8000 })
    await expect(page).toHaveURL(/login/)
  })

  test('rm_jane cannot reach an admin route by direct navigation', async ({ page }) => {
    // Log in as rm_jane against the chat/IAM to get a valid token in storage, then try the admin
    // route directly — the route guard must bounce her to /login.
    await page.goto(`${BASE}/login`)
    await page.getByLabel(/username/i).fill('rm_jane')
    await page.getByLabel(/password/i).fill(IAM_USER_PASSWORD)
    await page.getByRole('button', { name: /sign in/i }).click()
    await page.waitForTimeout(1000)
    await page.goto(`${BASE}/users`)
    await expect(page).toHaveURL(/login/)
  })

  // ── The segments-crash fix: each admin page must render its DATA, not the crash fallback ─────────
  test('Users page renders user rows (no segments crash)', async ({ page }) => {
    const errors = watchForCrashes(page)
    await loginAdmin(page)
    await page.getByRole('link', { name: /users/i }).click()
    await page.waitForURL(`${BASE}/users`)
    await assertNoCrash(page, errors)
    // The table body must actually render rows — the crash left tableRows=0 under the error boundary.
    await expect(page.locator('table tbody tr').first()).toBeVisible()
    expect(await page.locator('table tbody tr').count()).toBeGreaterThan(0)
  })

  test('Roles page renders (no crash)', async ({ page }) => {
    const errors = watchForCrashes(page)
    await loginAdmin(page)
    await page.getByRole('link', { name: /roles/i }).click()
    await page.waitForURL(`${BASE}/roles`)
    await page.waitForTimeout(1500)
    await assertNoCrash(page, errors)
  })

  test('Teams page renders (no crash)', async ({ page }) => {
    const errors = watchForCrashes(page)
    await loginAdmin(page)
    await page.getByRole('link', { name: /teams/i }).click()
    await page.waitForURL(`${BASE}/teams`)
    await page.waitForTimeout(1500)
    await assertNoCrash(page, errors)
  })

  test('Policies page renders (no crash)', async ({ page }) => {
    const errors = watchForCrashes(page)
    await loginAdmin(page)
    await page.getByRole('link', { name: /policies/i }).click()
    await page.waitForURL(`${BASE}/policies`)
    await page.waitForTimeout(1500)
    await assertNoCrash(page, errors)
  })

  test('Workbench renders with persona selector (no crash)', async ({ page }) => {
    const errors = watchForCrashes(page)
    await loginAdmin(page)
    await page.getByRole('link', { name: /workbench/i }).click()
    await page.waitForURL(`${BASE}/workbench`)
    await expect(page.getByRole('heading', { name: /conduit workbench/i })).toBeVisible()
    await expect(page.getByLabel(/workbench persona/i)).toBeVisible()
    await page.waitForTimeout(1000)
    await assertNoCrash(page, errors)
  })
})
