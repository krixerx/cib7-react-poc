import { defineConfig, devices } from '@playwright/test';

/**
 * End-to-end tests drive a real browser against a deployed stack, so they
 * catch what unit tests cannot: the Keycloak redirect, realm URLs and the SPA
 * boot working together. The target defaults to the public VM; point
 * E2E_BASE_URL elsewhere to test another deployment.
 */
export default defineConfig({
  testDir: './tests',
  timeout: 60_000,
  retries: 0,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'https://companylab.ai',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
});
