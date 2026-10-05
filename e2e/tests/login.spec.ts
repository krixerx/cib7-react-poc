import { expect, test } from '@playwright/test';

const username = process.env.E2E_APPLICANT_USER ?? 'bart';
const password = process.env.E2E_APPLICANT_PASSWORD ?? 'bart';

test('applicant logs in through Keycloak and returns to the SPA', async ({ page }) => {
  await page.goto('/');
  await page.getByRole('button', { name: 'Log in' }).click();

  await page.locator('#username').fill(username);
  await page.locator('#password').fill(password);
  await page.locator('#kc-login').click();

  await expect(page.locator('.app-user-name')).toHaveText(username);
});
