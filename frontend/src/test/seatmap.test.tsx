import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { SeatMapPage, seatsQuery } from '../pages/SeatMapPage';

const SEATS = [
  { id: 1, seatNo: 'A1', status: 'AVAILABLE' },
  { id: 2, seatNo: 'A2', status: 'AVAILABLE' },
  { id: 3, seatNo: 'A3', status: 'BOOKED' },
];

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

function renderPage(fetchImpl: (url: string, init?: RequestInit) => Promise<Response>) {
  vi.spyOn(globalThis, 'fetch').mockImplementation((input, init) => fetchImpl(String(input), init));
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/events/7']}>
        <Routes>
          <Route path="/events/:id" element={<SeatMapPage />} />
          <Route path="/bookings/:id" element={<p>booking page</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

afterEach(() => vi.restoreAllMocks());

describe('seat map', () => {
  it('polls every 2 s and pauses while the tab is hidden', () => {
    const q = seatsQuery(7);
    expect(q.refetchInterval).toBe(2000);
    expect(q.refetchIntervalInBackground).toBe(false);
  });

  it('renders a named, keyboard-operable grid and announces the selection', async () => {
    renderPage(async (url) => (url.endsWith('/seats') ? json(SEATS) : json({ id: 7, name: 'Show', priceCents: 2500 })));
    const a1 = await screen.findByRole('gridcell', { name: 'Row A seat 1, available' });
    expect(screen.getByRole('gridcell', { name: 'Row A seat 3, booked' })).toHaveAttribute('aria-disabled', 'true');

    a1.focus();
    await userEvent.keyboard(' ');
    expect(await screen.findByText('1 of 4 seats selected')).toHaveAttribute('aria-live', 'polite');
    await userEvent.keyboard('{ArrowRight} ');
    expect(screen.getByText('2 of 4 seats selected')).toBeInTheDocument();
    expect(screen.getByRole('gridcell', { name: 'Row A seat 2, selected' })).toHaveFocus();
  });

  it('a double-clicked Hold sends one request with one Idempotency-Key', async () => {
    const posts: RequestInit[] = [];
    renderPage(async (url, init) => {
      if (init?.method === 'POST') {
        posts.push(init);
        await new Promise((r) => setTimeout(r, 50));
        return json({ id: 'b-1', status: 'PENDING_PAYMENT' }, 201);
      }
      return url.endsWith('/seats') ? json(SEATS) : json({ id: 7, name: 'Show', priceCents: 2500 });
    });
    await userEvent.click(await screen.findByRole('gridcell', { name: 'Row A seat 1, available' }));
    await userEvent.dblClick(screen.getByRole('button', { name: /hold 1 seat/i }));

    expect(await screen.findByText('booking page')).toBeInTheDocument();
    expect(posts).toHaveLength(1);
    expect((posts[0].headers as Record<string, string>)['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/);
  });

  it('a seat taken first shows the friendly message and refreshes the map', async () => {
    let seatCalls = 0;
    renderPage(async (url, init) => {
      if (init?.method === 'POST') return json({ code: 'SEATS_UNAVAILABLE', status: 409 }, 409);
      if (url.endsWith('/seats')) { seatCalls++; return json(SEATS); }
      return json({ id: 7, name: 'Show', priceCents: 2500 });
    });
    await userEvent.click(await screen.findByRole('gridcell', { name: 'Row A seat 1, available' }));
    await userEvent.click(screen.getByRole('button', { name: /hold 1 seat/i }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Someone got those seats first');
    await waitFor(() => expect(seatCalls).toBeGreaterThanOrEqual(2));
  });

  it('a 429 disables Hold for the Retry-After seconds', async () => {
    renderPage(async (url, init) => {
      if (init?.method === 'POST') return new Response(JSON.stringify({ code: 'TOO_MANY_REQUESTS' }), { status: 429, headers: { 'Retry-After': '2' } });
      return url.endsWith('/seats') ? json(SEATS) : json({ id: 7, name: 'Show', priceCents: 2500 });
    });
    await userEvent.click(await screen.findByRole('gridcell', { name: 'Row A seat 1, available' }));
    await userEvent.click(screen.getByRole('button', { name: /hold 1 seat/i }));
    expect(await screen.findByText(/try again in 2 s/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /hold 1 seat/i })).toBeDisabled();
    await waitFor(() => expect(screen.getByRole('button', { name: /hold 1 seat/i })).toBeEnabled(), { timeout: 4000 });
  }, 10_000);
});
