import { expect, test, type APIRequestContext, type Page } from '@playwright/test';
import { readFileSync } from 'node:fs';

/**
 * Plan 8 "Done when" (spec §23.5): login -> hold -> pay -> confirmed, and one assistant question showing a source chip.
 * Needs the Compose stack (gateway on :8080). Run the two tests separately to spare memory:
 *   npx playwright test -g booking     (core stack)      npx playwright test -g assistant  (assistant subset)
 */
const GW = 'http://localhost:8080';
const env = Object.fromEntries(readFileSync('../.env', 'utf8').split('\n').filter((l) => l.includes('='))
  .map((l) => [l.slice(0, l.indexOf('=')).trim(), l.slice(l.indexOf('=') + 1).trim()]));

async function newUser(request: APIRequestContext) {
  const email = `ui-${Date.now()}-${Math.floor(Math.random() * 1e6)}@example.com`;
  const password = 'Smoke#12345';
  const r = await request.post(`${GW}/api/users/register`, { data: { email, password } });
  expect(r.status()).toBe(201);
  return { email, password };
}

async function signIn(page: Page, user: { email: string; password: string }) {
  await page.goto('/login');
  await page.getByLabel('Email').fill(user.email);
  await page.getByLabel('Password').fill(user.password);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByRole('heading', { name: 'Discover live events' })).toBeAttached();
}

test('booking: login, hold a seat, pay, confirmed', async ({ page, request }) => {
  const login = await request.post(`${GW}/api/users/login`, { data: { email: env.ADMIN_EMAIL, password: env.ADMIN_PASSWORD } });
  const admin = (await login.json()).accessToken as string;
  const event = await request.post(`${GW}/api/events`, {
    headers: { Authorization: `Bearer ${admin}` },
    data: {
      name: `UI smoke ${Date.now()}`, venue: 'Demo Hall', priceCents: 2500, rows: 1, seatsPerRow: 4,
      startsAt: new Date(Date.now() + 30 * 86_400_000).toISOString(),
      saleOpensAt: new Date(Date.now() - 5 * 60_000).toISOString(),   // Docker VM clock may lag the host
    },
  });
  expect(event.status()).toBe(201);
  const eventId = (await event.json()).id;

  await signIn(page, await newUser(request));
  await page.goto(`/events/${eventId}`);
  await page.getByRole('gridcell', { name: 'Row A seat 1, available' }).click();
  await expect(page.getByText('1 of 4 seats selected')).toBeVisible();
  await page.getByRole('button', { name: /hold 1 seat/i }).click();

  await expect(page.getByRole('heading', { name: 'Your booking' })).toBeVisible();
  await expect(page.getByTestId('countdown')).toBeVisible();
  await page.getByRole('button', { name: /pay \$25\.00/i }).click();
  await expect(page.getByText('Confirmed — the seats are yours.')).toBeVisible({ timeout: 30_000 });
});

test('assistant: a policy question shows a source chip', async ({ page, request }) => {
  await signIn(page, await newUser(request));
  await page.getByRole('button', { name: /open assistant/i }).click();
  await page.getByLabel('Ask a question').fill('How many seats can I book?');
  await page.getByRole('button', { name: 'Send' }).click();
  // with a real ANTHROPIC_API_KEY the chip comes from the answer's sources; without one, from the 503 fallback excerpts
  await expect(page.getByRole('list', { name: 'Sources' }).getByText('Booking policy')).toBeVisible({ timeout: 60_000 });
});
