import { expect, test } from '@playwright/test'

test('service is healthy and demo user can open the feed', async ({ page, request }) => {
  const health = await request.get(process.env.E2E_MANAGEMENT_URL
    || 'http://127.0.0.1:8081/actuator/health')
  expect(health.ok()).toBeTruthy()
  await expect(health.json()).resolves.toEqual(expect.objectContaining({ status: 'UP' }))

  await page.goto('/')
  await page.getByLabel('用户名').fill('demo_alice')
  await page.getByLabel('密码').fill('demo12345')
  const [loginResponse] = await Promise.all([
    page.waitForResponse(response => response.url().endsWith('/api/auth/login')),
    page.getByRole('button', { name: '进入 Friend Feed' }).click(),
  ])

  const loginBody = await loginResponse.json()
  expect(loginBody).not.toHaveProperty('refreshToken')
  const refreshCookie = (await page.context().cookies()).find(cookie => cookie.name === 'ff-refresh')
  expect(refreshCookie).toEqual(expect.objectContaining({
    httpOnly: true, sameSite: 'Strict', path: '/api/auth',
  }))
  const localStorageKeys = await page.evaluate(() => Object.keys(window.localStorage))
  expect(localStorageKeys.some(key => key.toLowerCase().includes('refresh'))).toBeFalsy()

  await expect(page.getByRole('heading', { name: '朋友动态' })).toBeVisible()
  await expect(page.getByText('demo_alice', { exact: false }).first()).toBeVisible()
})
