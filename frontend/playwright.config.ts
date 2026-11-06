import { defineConfig } from '@playwright/test';

/** Smoke against the real Compose stack through the Vite dev server (spec §23.5). */
const BASE = process.env.PW_BASE_URL ?? 'http://localhost:5173';   // e.g. a second dev server

export default defineConfig({
  testDir: 'e2e',
  timeout: 120_000,
  expect: { timeout: 30_000 },
  retries: 0,
  use: { baseURL: BASE, trace: 'retain-on-failure' },
  webServer: { command: 'npm run dev', url: BASE, reuseExistingServer: true, timeout: 120_000 },
  projects: [{ name: 'chromium', use: { browserName: 'chromium' } }],
});
