import { test, expect } from '@playwright/test'

const BASE = 'http://localhost:5180'

test.describe('Admin UI', () => {
  test('login page renders', async ({ page }) => {
    await page.goto(BASE)
    await expect(page).toHaveURL(/login/)
    await page.screenshot({ path: 'test-results/admin-01-login.png', fullPage: true })
  })

  test('login with admin credentials', async ({ page }) => {
    await page.goto(`${BASE}/login`)
    await page.getByLabel(/username/i).fill('admin')
    await page.getByLabel(/password/i).fill('Meridian@2024')
    await page.getByRole('button', { name: /sign in/i }).click()
    await expect(page).toHaveURL(`${BASE}/`, { timeout: 8000 })
    await page.screenshot({ path: 'test-results/admin-02-dashboard.png', fullPage: true })
  })

  test('dashboard shows stat cards', async ({ page }) => {
    await page.goto(`${BASE}/login`)
    await page.getByLabel(/username/i).fill('admin')
    await page.getByLabel(/password/i).fill('Meridian@2024')
    await page.getByRole('button', { name: /sign in/i }).click()
    await page.waitForURL(`${BASE}/`)
    await expect(page.getByText(/users/i).first()).toBeVisible()
    await page.screenshot({ path: 'test-results/admin-03-dashboard-cards.png', fullPage: true })
  })

  test('users page loads', async ({ page }) => {
    await page.goto(`${BASE}/login`)
    await page.getByLabel(/username/i).fill('admin')
    await page.getByLabel(/password/i).fill('Meridian@2024')
    await page.getByRole('button', { name: /sign in/i }).click()
    await page.waitForURL(`${BASE}/`)
    await page.getByRole('link', { name: /users/i }).click()
    await page.waitForURL(`${BASE}/users`)
    await page.screenshot({ path: 'test-results/admin-04-users.png', fullPage: true })
  })

  test('teams page loads', async ({ page }) => {
    await page.goto(`${BASE}/login`)
    await page.getByLabel(/username/i).fill('admin')
    await page.getByLabel(/password/i).fill('Meridian@2024')
    await page.getByRole('button', { name: /sign in/i }).click()
    await page.waitForURL(`${BASE}/`)
    await page.getByRole('link', { name: /teams/i }).click()
    await page.waitForURL(`${BASE}/teams`)
    await page.screenshot({ path: 'test-results/admin-05-teams.png', fullPage: true })
  })

  test('roles page loads', async ({ page }) => {
    await page.goto(`${BASE}/login`)
    await page.getByLabel(/username/i).fill('admin')
    await page.getByLabel(/password/i).fill('Meridian@2024')
    await page.getByRole('button', { name: /sign in/i }).click()
    await page.waitForURL(`${BASE}/`)
    await page.getByRole('link', { name: /roles/i }).click()
    await page.waitForURL(`${BASE}/roles`)
    await page.screenshot({ path: 'test-results/admin-06-roles.png', fullPage: true })
  })

  test('policies page loads', async ({ page }) => {
    await page.goto(`${BASE}/login`)
    await page.getByLabel(/username/i).fill('admin')
    await page.getByLabel(/password/i).fill('Meridian@2024')
    await page.getByRole('button', { name: /sign in/i }).click()
    await page.waitForURL(`${BASE}/`)
    await page.getByRole('link', { name: /policies/i }).click()
    await page.waitForURL(`${BASE}/policies`)
    await page.screenshot({ path: 'test-results/admin-07-policies.png', fullPage: true })
  })
})
