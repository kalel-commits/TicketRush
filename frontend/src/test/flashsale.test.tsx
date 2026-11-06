import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { FlashSaleDemoPage } from '../pages/FlashSaleDemoPage';

const BOOK_A = '0195f3a2-b4c4-7d1e-8f00-1122aabbccdd';
const TOKENS = { A: 'tok-A-secret-jwt', B: 'tok-B-secret-jwt' };

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

/** A fake backend that behaves like the real one: first hold wins, the second gets 409; the observer reflects it. */
function backend() {
  const state = { raced: false, keyA: '', keyB: '', respondedAt: 0 };
  const seat = (status: string) => [{ id: 10, seatNo: 'A1', status }, { id: 11, seatNo: 'A2', status: 'AVAILABLE' }];
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    const url = String(input);
    const auth = (init?.headers as Record<string, string> | undefined)?.Authorization ?? '';
    if (url.endsWith('/__demo/race/setup')) {
      return json({ eventId: 9, buyers: [{ label: 'A', userId: 1, token: TOKENS.A }, { label: 'B', userId: 2, token: TOKENS.B }] });
    }
    if (url.endsWith('/__demo/provider')) return json({ mode: 'succeed', behaviour: {} });
    if (url.includes('/__demo/observe')) {
      const after = state.raced && Date.now() > state.respondedAt;
      return json({
        startedAt: Date.now(),
        takenAt: Date.now(),
        readers: { db: true, redis: true },
        locks: after ? [{ seatId: 10, ownerUserId: 1, ownerKey: state.keyA, pttlMs: 650_000 }] : [],
        seats: [{ id: 10, seatNo: 'A1', status: after ? 'HELD' : 'AVAILABLE', bookingId: after ? BOOK_A : null, heldUntil: null },
          { id: 11, seatNo: 'A2', status: 'AVAILABLE', bookingId: null, heldUntil: null }],
        bookings: after ? [{ id: BOOK_A, userId: 1, idempotencyKey: state.keyA, status: 'PENDING_PAYMENT', failureReason: null, createdAt: '', updatedAt: '', seatIds: [10] }] : [],
        payments: [],
      });
    }
    if (url.endsWith('/api/events/9/seats')) return json(seat(state.raced ? 'HELD' : 'AVAILABLE'));
    if (url.endsWith('/api/bookings') && init?.method === 'POST') {
      const key = (init.headers as Record<string, string>)['Idempotency-Key'];
      state.raced = true;
      state.respondedAt = Date.now();
      if (auth.endsWith(TOKENS.A)) {
        state.keyA = key;
        return json({ id: BOOK_A, eventId: 9, status: 'PENDING_PAYMENT', seatIds: [10], seatNos: ['A1'], amountCents: 2500, expiresAt: new Date(Date.now() + 600_000).toISOString() }, 201);
      }
      state.keyB = key;
      return json({ code: 'SEATS_UNAVAILABLE', status: 409, detail: 'One or more seats are no longer available' }, 409);
    }
    if (url.endsWith(`/api/bookings/${BOOK_A}`)) {
      return json({ id: BOOK_A, eventId: 9, status: 'PENDING_PAYMENT', seatIds: [10], seatNos: ['A1'], amountCents: 2500, expiresAt: new Date(Date.now() + 600_000).toISOString() });
    }
    return json({ code: 'NOT_FOUND' }, 404);
  });
  return state;
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={client}><MemoryRouter><FlashSaleDemoPage /></MemoryRouter></QueryClientProvider>);
}

afterEach(() => vi.restoreAllMocks());

describe('flash-sale demo: two buyers, one seat', () => {
  it('both see A1 available, race for it, exactly one wins and the loser cannot pay', async () => {
    const state = backend();
    const { container } = renderPage();
    await userEvent.click(screen.getByRole('button', { name: /start a new race/i }));

    const a = await screen.findByRole('region', { name: 'Buyer A' });
    const b = screen.getByRole('region', { name: 'Buyer B' });
    expect(await within(a).findByRole('button', { name: 'A1, available' })).toBeInTheDocument();
    expect(await within(b).findByRole('button', { name: 'A1, available' })).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: /race both buyers for A1/i }));

    expect(await within(a).findByText(/you got seat A1/i)).toBeInTheDocument();
    expect(await within(b).findByText(/A1 is no longer available/i)).toBeInTheDocument();
    expect(within(a).getByRole('button', { name: /pay \$25\.00/i })).toBeInTheDocument();
    expect(within(b).queryByRole('button', { name: /pay/i })).toBeNull();
    expect(state.keyA).not.toBe(state.keyB);   // two independent requests

    // B's map updates from its own polling: the seat is now held
    expect(await within(b).findByRole('button', { name: 'A1, held' }, { timeout: 4000 })).toBeInTheDocument();
    expect(container.innerHTML).not.toContain(TOKENS.A);   // tokens are never rendered
    expect(container.innerHTML).not.toContain(TOKENS.B);
  });

  it('explains the outcome only with backend evidence: Redis key owner, MySQL seat row, no booking row for the loser', async () => {
    backend();
    renderPage();
    await userEvent.click(screen.getByRole('button', { name: /start a new race/i }));
    await screen.findByRole('region', { name: 'Buyer A' });
    await userEvent.click(await screen.findByRole('button', { name: /race both buyers for A1/i }));

    const timeline = screen.getByRole('region', { name: /behind the scenes/i });
    await waitFor(() => expect(within(timeline).getByTestId('stage-A-redis')).toHaveTextContent(/lock acquired/i), { timeout: 5000 });
    expect(within(timeline).getByTestId('stage-A-mysql')).toHaveTextContent(/AVAILABLE → HELD/);
    expect(within(timeline).getByTestId('stage-B-redis')).toHaveTextContent(/rejected/i);
    expect(within(timeline).getByTestId('stage-B-redis')).toHaveTextContent(/user 1/);
    expect(within(timeline).getByTestId('stage-B-mysql')).toHaveTextContent(/not reached/i);
    expect(within(timeline).getByTestId('stage-B-http')).toHaveTextContent('409');
  }, 15_000);

  it('labels data honestly as polled from the real backend', async () => {
    backend();
    renderPage();
    expect(screen.getByText(/polled every 1 s/i)).toBeInTheDocument();
  });
});

describe('flash-sale demo: failures are visible (I2)', () => {
  it('an observer that cannot read the stores says so instead of showing stale data', async () => {
    const original = backend();
    void original;
    const real = globalThis.fetch;
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) =>
      String(input).includes('/__demo/observe') ? json({ code: 'HARNESS_ERROR', message: 'docker compose exec failed' }, 502) : real(input, init));
    renderPage();
    await userEvent.click(screen.getByRole('button', { name: /start a new race/i }));
    const observer = await screen.findByRole('region', { name: /system observer/i });
    expect(await within(observer).findByRole('alert')).toHaveTextContent(/observer unavailable.*docker compose exec failed/i);
  });
});

describe('flash-sale demo: load test', () => {
  it('warns about real bookings, streams progress and shows the SQL-verified result', async () => {
    backend();
    const result = {
      eventId: 12, config: { buyers: 100, seats: 20, pay: false },
      race: { total: 100, byStatus: { 201: 20, 409: 80 }, p50: 40, p95: 120, p99: 180, max: 210, wallMs: 450, codes: { SEATS_UNAVAILABLE: 80 } },
      evidence: { activeBookings: 20, seatsTaken: 20, mysqlRejected: 3, redisRejected: 77, otherFailures: 0, mismatches: [], duplicateSeats: 0 },
      payments: null, invariants: { pass: true, violations: [] },
    };
    const sse = `event:progress\ndata:${JSON.stringify({ phase: 'race', message: '100 buyers → 20 seats, all at once' })}\n\n` +
      `event:result\ndata:${JSON.stringify(result)}\n\n`;
    const original = globalThis.fetch;
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) =>
      String(input).endsWith('/__demo/load') ? new Response(sse, { status: 200, headers: { 'Content-Type': 'text/event-stream' } }) : original(input, init));
    renderPage();
    const panel = screen.getByRole('region', { name: /load test/i });
    expect(within(panel).getByText(/creates real demo bookings/i)).toBeInTheDocument();
    await userEvent.click(within(panel).getByRole('button', { name: /run load test/i }));
    expect(await within(panel).findByTestId('kpi-won')).toHaveTextContent('20');
    expect(within(panel).getByTestId('kpi-rejected')).toHaveTextContent('80');
    expect(within(panel).getByTestId('kpi-duplicates')).toHaveTextContent('0');
    expect(within(panel).getByText(/invariants.*pass/i)).toBeInTheDocument();
  });
});
