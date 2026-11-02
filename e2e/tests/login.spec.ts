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

test('applicant sees the correct navigation after login', async ({ page }) => {
  await page.goto('/');
  await page.getByRole('button', { name: 'Log in' }).click();

  await page.locator('#username').fill(username);
  await page.locator('#password').fill(password);
  await page.locator('#kc-login').click();

  await expect(page.locator('.app-user-name')).toHaveText(username);

  const nav = page.getByRole('navigation', { name: 'Main' }).first();

  // Applicant nav: Services, My cases, My files (no Incidents).
  await expect(nav.getByRole('link', { name: /Services/i })).toBeVisible();
  await expect(nav.getByRole('link', { name: /My cases/i })).toBeVisible();
  await expect(nav.getByRole('link', { name: /My files/i })).toBeVisible();
  await expect(nav.getByRole('link', { name: /Incidents/i })).not.toBeVisible();
});
