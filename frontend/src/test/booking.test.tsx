import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { BookingPage } from '../pages/BookingPage';

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

const pending = () => ({
  id: 'b1', eventId: 7, status: 'PENDING_PAYMENT', seatNos: ['A1'], amountCents: 2500,
  expiresAt: new Date(Date.now() + 8 * 60_000).toISOString(),
});

function renderPage(fetchImpl: (url: string, init?: RequestInit) => Promise<Response>) {
  vi.spyOn(globalThis, 'fetch').mockImplementation((input, init) => fetchImpl(String(input), init));
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/bookings/b1']}>
        <Routes>
          <Route path="/bookings/:id" element={<BookingPage />} />
          <Route path="/events/:id" element={<p>seat map</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

afterEach(() => vi.restoreAllMocks());

describe('hold & pay', () => {
  it('a double-clicked Pay sends one payment, and a retry after a decline uses a new key', async () => {
    const keys: string[] = [];
    let attempt = 0;
    renderPage(async (_url, init) => {
      if (init?.method === 'POST') {
        keys.push((init.headers as Record<string, string>)['Idempotency-Key']);
        attempt++;
        await new Promise((r) => setTimeout(r, 30));
        return attempt === 1
          ? json({ code: 'DECLINED', status: 402, paymentId: 'p1' }, 402)
          : json({ id: 'p2', status: 'SUCCEEDED' }, 200);
      }
      return json(pending());
    });

    await userEvent.dblClick(await screen.findByRole('button', { name: /pay \$25\.00/i }));
    expect(await screen.findByText(/declined/i)).toBeInTheDocument();
    expect(keys).toHaveLength(1);

    await userEvent.click(screen.getByRole('button', { name: /pay \$25\.00/i }));
    await waitFor(() => expect(keys).toHaveLength(2));
    expect(keys[1]).not.toBe(keys[0]);
  });

  it('an expired hold disables pay and offers new seats', async () => {
    renderPage(async () => json({ ...pending(), status: 'EXPIRED', expiresAt: new Date(Date.now() - 60_000).toISOString() }));
    expect(await screen.findByText(/hold has expired/i)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /pay/i })).toBeNull();
    expect(screen.getByRole('link', { name: /pick new seats/i })).toHaveAttribute('href', '/events/7');
  });

  it('shows the countdown in tabular digits', async () => {
    renderPage(async () => json(pending()));
    const clock = await screen.findByTestId('countdown');
    expect(clock.textContent).toMatch(/^0[78]:\d\d$/);
    expect(clock.className).toContain('num');
  });

  it('a network error retries with the same key; a stored 503 (paymentId) makes the next try a new attempt', async () => {
    const keys: string[] = [];
    renderPage(async (_url, init) => {
      if (init?.method === 'POST') {
        keys.push((init.headers as Record<string, string>)['Idempotency-Key']);
        if (keys.length === 1) throw new TypeError('Failed to fetch');
        if (keys.length === 2) return json({ code: 'DEPENDENCY_UNAVAILABLE', status: 503, paymentId: 'p1' }, 503);
        return json({ id: 'p2', status: 'SUCCEEDED' });
      }
      return json(pending());
    });
    const pay = await screen.findByRole('button', { name: /pay \$25\.00/i });
    await userEvent.click(pay);
    await waitFor(() => expect(keys).toHaveLength(1));
    await userEvent.click(await screen.findByRole('button', { name: /pay \$25\.00/i }));
    await waitFor(() => expect(keys).toHaveLength(2));
    expect(keys[1]).toBe(keys[0]);   // same attempt: the server may never have seen it
    await userEvent.click(await screen.findByRole('button', { name: /pay \$25\.00/i }));
    await waitFor(() => expect(keys).toHaveLength(3));
    expect(keys[2]).not.toBe(keys[1]);   // the 503 was a stored answer: replaying its key can only repeat it
  });

  it('a PROCESSING payment is polled until it settles, then polling stops', async () => {
    let gets = 0;
    renderPage(async (url, init) => {
      if (init?.method === 'POST') return json({ id: 'p1', status: 'PROCESSING' }, 202);
      if (url.includes('/api/payments/p1')) {
        gets++;
        return json({ id: 'p1', status: gets >= 2 ? 'SUCCEEDED' : 'PROCESSING' });
      }
      return json(pending());
    });
    await userEvent.click(await screen.findByRole('button', { name: /pay \$25\.00/i }));
    await waitFor(() => expect(gets).toBeGreaterThanOrEqual(2), { timeout: 8000 });
    const settled = gets;
    await new Promise((r) => setTimeout(r, 2600));
    expect(gets).toBe(settled);
    expect(screen.getByText(/confirming with the bank/i)).toBeInTheDocument();
  }, 20_000);
});
