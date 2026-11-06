import { expect, test, type Page } from '@playwright/test';

/**
 * Plan 9 acceptance on the REAL stack (docker compose up) through the dev server (npm run dev, which hosts the harness).
 *   npx playwright test e2e/flashsale.spec.ts
 */
test.describe.configure({ mode: 'serial' });

async function setMode(page: Page, title: RegExp) {
  const scenarios = page.getByRole('region', { name: 'Payment scenarios' });
  await scenarios.getByRole('button', { name: title }).click();
  await expect(scenarios.getByRole('button', { name: title })).toHaveAttribute('aria-pressed', 'true', { timeout: 20_000 });
}

test('two buyers race for one seat: exactly one wins, the other is refused, and the backend evidence says why', async ({ page }) => {
  test.setTimeout(240_000);
  const errors: string[] = [];
  page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
  await page.goto('/demo/flash-sale');
  await setMode(page, /succeeds/i);
  await page.getByRole('button', { name: /start a new race/i }).click();
  const a = page.getByRole('region', { name: 'Buyer A' });
  const b = page.getByRole('region', { name: 'Buyer B' });
  await expect(a.getByRole('button', { name: 'A1, available' })).toBeVisible({ timeout: 120_000 });
  await expect(b.getByRole('button', { name: 'A1, available' })).toBeVisible();

  await page.getByRole('button', { name: /race both buyers for A1/i }).click();
  const winA = a.getByText(/you got seat A1/i);
  const winB = b.getByText(/you got seat A1/i);
  const loseA = a.getByText(/A1 is no longer available/i);
  const loseB = b.getByText(/A1 is no longer available/i);
  await expect(winA.or(winB)).toBeVisible({ timeout: 30_000 });
  await expect(loseA.or(loseB)).toBeVisible({ timeout: 30_000 });
  const aWon = await winA.isVisible();
  expect(aWon ? await loseB.isVisible() : await loseA.isVisible()).toBe(true);   // exactly one winner
  const [winner, loser, W, L] = aWon ? [a, b, 'A', 'B'] : [b, a, 'B', 'A'];
  await expect(loser.getByRole('button', { name: /^pay/i })).toHaveCount(0);
  await expect(loser.getByRole('button', { name: 'A1, held' })).toBeVisible({ timeout: 10_000 });

  // evidence from the stores: the winner's seat row in event_db points at its booking; the loser was stopped somewhere real
  const timeline = page.getByRole('region', { name: 'Behind the scenes' });
  await expect(timeline.getByTestId(`stage-${W}-mysql`)).toContainText(/AVAILABLE → HELD/, { timeout: 15_000 });
  await expect(timeline.getByTestId(`stage-${L}-http`)).toContainText('409');
  await expect(timeline.getByTestId(`stage-${L}-redis`).or(timeline.getByTestId(`stage-${L}-mysql`))
    .filter({ hasText: /rejected|refused/i }).first()).toBeVisible({ timeout: 15_000 });

  // the winner pays: payment → Kafka → CONFIRMED → seat BOOKED, all read back from the backend
  await winner.getByRole('button', { name: /pay \$25\.00/i }).click();
  await expect(winner.getByText(/booking confirmed/i)).toBeVisible({ timeout: 45_000 });
  await expect(timeline.getByTestId(`stage-${W}-kafka`)).toContainText(/seat BOOKED/, { timeout: 30_000 });
  await expect(loser.getByRole('button', { name: 'A1, booked' })).toBeVisible({ timeout: 10_000 });
  // Chrome logs the loser's EXPECTED 409 as a failed resource; anything else is a real error
  expect(errors.filter((e) => !/favicon|status of 409/i.test(e))).toEqual([]);
});

test('100 buyers vs 20 seats: at most 20 holds, zero double bookings, invariants pass', async ({ page }) => {
  test.setTimeout(300_000);
  await page.goto('/demo/flash-sale');
  const panel = page.getByRole('region', { name: 'Flash-sale load test' });
  await panel.getByLabel('Buyers').fill('100');
  await panel.getByLabel('Seats').fill('20');
  await panel.getByRole('button', { name: /run load test/i }).click();
  await expect(panel.getByTestId('kpi-won')).toBeVisible({ timeout: 240_000 });
  const won = Number(await panel.getByTestId('kpi-won').textContent());
  expect(won).toBeGreaterThan(0);
  expect(won).toBeLessThanOrEqual(20);
  await expect(panel.getByTestId('kpi-duplicates')).toHaveText('0');
  await expect(panel.getByTestId('kpi-total')).toHaveText('100');
  await expect(panel.getByText(/invariants.*all pass/i)).toBeVisible();
});

test('payment scenarios on the real provider: a decline, then an ambiguous timeout the reconciler settles', async ({ page }) => {
  test.setTimeout(300_000);
  await page.goto('/demo/flash-sale');
  try {
    await setMode(page, /declined/i);
    await page.getByRole('button', { name: /start a new race/i }).click();
    const a = page.getByRole('region', { name: 'Buyer A' });
    await a.getByRole('button', { name: 'A2, available' }).click({ timeout: 120_000 });
    await a.getByRole('button', { name: /book A2/i }).click();
    await expect(a.getByText(/you got seat A2/i)).toBeVisible({ timeout: 30_000 });

    await a.getByRole('button', { name: /pay \$25\.00/i }).click();
    await expect(a.getByText(/402 DECLINED/)).toBeVisible({ timeout: 30_000 });
    const timeline = page.getByRole('region', { name: 'Behind the scenes' });
    await expect(timeline.getByTestId('stage-A-payment')).toContainText(/FAILED.*DECLINED/, { timeout: 15_000 });

    // money taken, answer too late: PROCESSING until the reconciler (30 s) looks the charge up
    await setMode(page, /times out after charging/i);
    await a.getByRole('button', { name: /pay \$25\.00/i }).click();
    await expect(a.getByText(/Payment: PROCESSING/)).toBeVisible({ timeout: 30_000 });
    await expect(a.getByText(/booking confirmed/i)).toBeVisible({ timeout: 120_000 });
    await expect(timeline.getByTestId('stage-A-payment')).toContainText(/SUCCEEDED/, { timeout: 15_000 });
  } finally {
    await setMode(page, /succeeds/i);   // leave the provider healthy
  }
});
