import { expect, test } from '@playwright/test'

test('service is healthy and demo user can open the feed', async ({ page, request }) => {
  const health = await request.get('/actuator/health')
  expect(health.ok()).toBeTruthy()
  await expect(health.json()).resolves.toEqual(expect.objectContaining({ status: 'UP' }))

  await page.goto('/')
  await page.getByLabel('用户名').fill('demo_alice')
  await page.getByLabel('密码').fill('demo12345')
  await page.getByRole('button', { name: '进入 Friend Feed' }).click()

  await expect(page.getByRole('heading', { name: '朋友动态' })).toBeVisible()
  await expect(page.getByText('demo_alice', { exact: false }).first()).toBeVisible()
})
