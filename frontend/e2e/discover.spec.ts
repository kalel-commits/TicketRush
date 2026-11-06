import { expect, test, type APIRequestContext, type Page } from '@playwright/test';
import { mkdirSync } from 'node:fs';

/**
 * Customer redesign checks on the real stack (gateway :8080, dev server :5173), with the demo catalog seeded
 * (`npm run seed:catalog`). Saves screenshots to docs/screenshots/.
 */
const GW = 'http://localhost:8080';
const SHOTS = '../docs/screenshots';
mkdirSync(SHOTS, { recursive: true });
test.describe.configure({ mode: 'serial' });

async function newUser(request: APIRequestContext) {
  const email = `fan-${Date.now()}-${Math.floor(Math.random() * 1e6)}@example.com`;
  const password = 'Concert#2026';
  expect((await request.post(`${GW}/api/users/register`, { data: { email, password } })).status()).toBe(201);
  return { email, password };
}

async function signIn(page: Page, user: { email: string; password: string }) {
  await page.goto('/login');
  await page.getByLabel('Email').fill(user.email);
  await page.getByLabel('Password').fill(user.password);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByRole('heading', { name: 'Discover live events' })).toBeAttached();
}

const cards = (page: Page) => page.getByRole('list').filter({ has: page.getByRole('article') }).getByRole('article');

test('login and logout work, and signed-out visitors are sent to sign in', async ({ page, request }) => {
  const user = await newUser(request);
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto('/login');
  await page.screenshot({ path: `${SHOTS}/00-sign-in.png` });
  await signIn(page, user);
  await page.getByRole('button', { name: user.email }).click();
  await page.getByRole('menuitem', { name: 'Sign out' }).click();
  await expect(page).toHaveURL(/\/login$/);
  await page.goto('/events');
  await expect(page).toHaveURL(/\/login$/);   // auth still required
});

test.describe('signed in', () => {
  let user: { email: string; password: string };
  test.beforeAll(async ({ request }) => { user = await newUser(request); });
  test.beforeEach(async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await signIn(page, user);
  });

  test('the homepage shows the hero and the catalog, never test or load-test events', async ({ page }) => {
    await expect(page.getByRole('region', { name: /./ }).getByRole('link', { name: /get tickets/i })).toBeVisible();
    await expect(cards(page).first()).toBeVisible();
    expect(await cards(page).count()).toBeGreaterThanOrEqual(9);
    await expect(page.getByText(/Flash sale · race|Load test|Demo warm-up|UI smoke/)).toHaveCount(0);
    await expect(page.getByText(/Demo environment/)).toBeVisible();
    await page.waitForTimeout(600);   // let the card entrance animation finish
    await page.screenshot({ path: `${SHOTS}/01-home.png` });
    await page.screenshot({ path: `${SHOTS}/01-home-full.png`, fullPage: true });
  });

  test('search finds events by artist, venue and city', async ({ page }) => {
    const search = page.getByRole('searchbox', { name: 'Search', exact: true });
    await search.fill('arijit');
    await expect(cards(page)).toHaveCount(1);
    await expect(cards(page).first()).toContainText('Arijit Singh');
    await search.fill('goa');
    await expect(cards(page)).toHaveCount(1);
    await expect(cards(page).first()).toContainText('Sunburn');
    await search.fill('no such artist anywhere');
    await expect(page.getByText('No events match your search')).toBeVisible();
    await page.getByRole('button', { name: 'Clear filters' }).first().click();
    expect(await cards(page).count()).toBeGreaterThanOrEqual(9);
  });

  test('category and availability filters narrow the grid', async ({ page }) => {
    await page.getByRole('button', { name: 'Festivals' }).click();
    await expect(page.getByRole('button', { name: 'Festivals' })).toHaveAttribute('aria-pressed', 'true');
    await expect(cards(page)).toHaveCount(1);
    await expect(cards(page).first()).toContainText('Sunburn');
    await page.getByRole('button', { name: 'Bollywood' }).click();
    await expect(cards(page)).toHaveCount(2);
    await page.getByRole('button', { name: 'All Events' }).click();
    const all = await cards(page).count();
    await page.getByLabel('Availability').selectOption('onsale');
    await expect(cards(page)).toHaveCount(all - 1);   // the not-yet-on-sale rock night drops out
    await page.getByLabel('Price').selectOption('over100');
    await expect(cards(page).first()).toContainText(/Taylor Swift|The Weeknd/);
  });

  test('event details, seat selection, booking and My bookings', async ({ page }) => {
    test.setTimeout(120_000);
    await page.getByRole('searchbox', { name: 'Search', exact: true }).fill('coldplay');
    await cards(page).first().getByRole('link').click();
    await expect(page.getByRole('heading', { level: 1, name: /Coldplay — Music of the Spheres/ })).toBeVisible();
    await expect(page.getByText(/LED wristbands/)).toBeVisible();
    await expect(page.getByText('Available seats')).toBeVisible();
    await page.waitForTimeout(400);
    await page.screenshot({ path: `${SHOTS}/02-event-details.png` });

    await page.getByRole('button', { name: 'Book tickets' }).click();
    const seat = page.getByRole('gridcell', { name: /, available$/ }).first();
    await seat.click();
    await expect(page.getByText('1 of 4 seats selected')).toBeVisible();
    await page.evaluate(() => document.getElementById('seats')?.scrollIntoView({ block: 'start', behavior: 'instant' }));
    await page.waitForTimeout(800);   // smooth scroll settles before the screenshot
    await expect(page.getByRole('complementary', { name: 'Your selection' })).toContainText('$89.00');
    await page.screenshot({ path: `${SHOTS}/03-seat-selection.png` });

    await page.getByRole('button', { name: /hold 1 seat/i }).click();
    await expect(page.getByRole('heading', { name: 'Your booking' })).toBeVisible();
    await expect(page.getByText(/Coldplay — Music of the Spheres/)).toBeVisible();
    await page.getByRole('button', { name: /pay \$89\.00/i }).click();
    await expect(page.getByText('Confirmed — the seats are yours.')).toBeVisible({ timeout: 30_000 });
    await page.screenshot({ path: `${SHOTS}/04-booking-confirmed.png` });

    await page.getByRole('link', { name: 'My bookings' }).first().click();
    await expect(page.getByRole('heading', { name: 'My bookings' })).toBeVisible();
    await expect(page.getByRole('link', { name: /Coldplay — Music of the Spheres/ })).toBeVisible();
    await expect(page.getByText('Confirmed').first()).toBeVisible();
    await page.screenshot({ path: `${SHOTS}/05-my-bookings.png` });
  });

  test('the live concurrency demo still works on its own page', async ({ page }) => {
    test.setTimeout(240_000);
    await page.goto('/demo/flash-sale');
    await page.getByRole('button', { name: /start a new race/i }).click();
    const a = page.getByRole('region', { name: 'Buyer A' });
    const b = page.getByRole('region', { name: 'Buyer B' });
    await expect(a.getByRole('button', { name: 'A1, available' })).toBeVisible({ timeout: 120_000 });
    await page.getByRole('button', { name: /race both buyers for A1/i }).click();
    await expect(a.getByText(/you got seat A1/i).or(b.getByText(/you got seat A1/i))).toBeVisible({ timeout: 30_000 });
    await expect(a.getByText(/A1 is no longer available/i).or(b.getByText(/A1 is no longer available/i))).toBeVisible({ timeout: 30_000 });
    await expect(page.getByRole('region', { name: 'System observer' })).toBeVisible();
    await page.waitForTimeout(2500);   // one more observer poll so the evidence is filled in
    await page.screenshot({ path: `${SHOTS}/06-live-demo.png` });
    await page.screenshot({ path: `${SHOTS}/06-live-demo-full.png`, fullPage: true });
  });

  test('mobile: no horizontal overflow, menu works, cards stack', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto('/events');
    await expect(cards(page).first()).toBeVisible();
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
    expect(overflow).toBeLessThanOrEqual(0);
    await page.waitForTimeout(600);
    await page.screenshot({ path: `${SHOTS}/07-mobile-home.png` });
    await page.getByRole('button', { name: 'Open menu' }).click();
    await expect(page.locator('#mobile-menu').getByRole('link', { name: 'My bookings' })).toBeVisible();
    await page.screenshot({ path: `${SHOTS}/08-mobile-menu.png` });
    await page.locator('#mobile-menu').getByRole('link', { name: 'Discover events' }).click();
    await cards(page).first().getByRole('link').click();
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)).toBeLessThanOrEqual(0);
    await page.screenshot({ path: `${SHOTS}/09-mobile-event.png` });
  });
});
