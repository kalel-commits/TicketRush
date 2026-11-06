import { CheckCircle, Clock } from '@phosphor-icons/react';
import { useQuery } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api, ApiError } from '../api/client';
import { announcement, formatClock, payState, type Booking, type Payment } from '../booking/payState';
import { Button, Card, ErrorNotice, StatusBadge } from '../components/ui';

const FINAL = new Set(['CONFIRMED', 'EXPIRED', 'CANCELLED', 'FAILED']);

export function BookingPage() {
  const id = useParams().id!;
  const booking = useQuery({
    queryKey: ['booking', id],
    queryFn: () => api<Booking>(`/api/bookings/${id}`),
    refetchInterval: (q) => (q.state.data && FINAL.has(q.state.data.status) ? false : 2000),
  });
  const [payment, setPayment] = useState<Payment | null>(null);
  const paymentQuery = useQuery({
    queryKey: ['payment', payment?.id],
    queryFn: () => api<Payment>(`/api/payments/${payment!.id}`),
    enabled: !!payment && payment.status === 'PROCESSING',
    refetchInterval: (q) => (q.state.data && q.state.data.status !== 'PROCESSING' ? false : 2000),   // stop once settled
  });
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const inFlight = useRef(false);
  const attemptKey = useRef<string | null>(null);   // reused only when the same attempt is retried after a network error
  const [now, setNow] = useState(Date.now());
  const [spoken, setSpoken] = useState('');

  useEffect(() => { const t = setInterval(() => setNow(Date.now()), 1000); return () => clearInterval(t); }, []);
  const latestPayment = paymentQuery.data ?? payment;
  const b = booking.data;
  // display only: the event's name above the checkout (a failed lookup simply omits it)
  const eventQ = useQuery({
    queryKey: ['event', b?.eventId], queryFn: () => api<{ name: string }>(`/api/events/${b!.eventId}`),
    enabled: !!b, retry: false, staleTime: 60_000,
  });
  const eventName = typeof eventQ.data?.name === 'string' ? eventQ.data.name : null;
  const secondsLeft = b ? Math.floor((Date.parse(b.expiresAt) - now) / 1000) : 0;
  // only a loaded, still-open hold is announced: while loading secondsLeft is 0 and would falsely say "expired"
  const live = !!b && !FINAL.has(b.status);
  useEffect(() => { if (!live) return; const a = announcement(secondsLeft); if (a) setSpoken(a); }, [secondsLeft, live]);

  if (booking.isLoading) return <p>Loading booking…</p>;
  if (booking.error || !b) return <ErrorNotice error={booking.error} />;

  const lastCode = error instanceof ApiError ? error.code : null;
  const state = payState(b, latestPayment, lastCode, now);
  const price = b.amountCents != null ? `$${(b.amountCents / 100).toFixed(2)}` : '';

  async function pay() {
    if (inFlight.current) return;   // a double click never sends a second payment
    inFlight.current = true;
    setBusy(true);
    setError(null);
    attemptKey.current ??= crypto.randomUUID();
    try {
      const p = await api<Payment>('/api/payments', { method: 'POST', body: { bookingId: id }, headers: { 'Idempotency-Key': attemptKey.current } });
      attemptKey.current = null;   // a definitive answer: any further try is a new attempt
      setPayment(p);
      void booking.refetch();
    } catch (err) {
      setError(err);
      // Keep the key only when the server may not have seen the attempt (network error, or a 5xx with no stored
      // payment). A 4xx, or any answer naming a paymentId, is stored: replaying its key can only repeat it.
      const stored = err instanceof ApiError && (err.status < 500 || err.body.paymentId != null);
      if (stored) attemptKey.current = null;
    } finally {
      inFlight.current = false;
      setBusy(false);
    }
  }

  return (
    <div className="mx-auto flex max-w-xl flex-col gap-4">
      <div>
        <p className="text-sm font-semibold uppercase tracking-widest text-accent-2">Checkout</p>
        <h1 className="text-3xl font-bold tracking-tight">Your booking</h1>
        {eventName && <p className="mt-1 text-muted-fg">{eventName}</p>}
      </div>
      <Card className="flex flex-col gap-3">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <StatusBadge status={b.status} />
          {!FINAL.has(b.status) && (
            <span className="flex items-center gap-2 text-lg">
              <Clock aria-hidden />
              <span data-testid="countdown" className="num" aria-label="Time left to pay">{formatClock(secondsLeft)}</span>
            </span>
          )}
        </div>
        <p>Seats <span className="num">{b.seatNos.join(', ')}</span>{price && <> · total <span className="num">{price}</span></>}</p>
        <p className={state.kind === 'confirmed' ? 'flex items-center gap-2 text-accent' : ''}>
          {state.kind === 'confirmed' && <CheckCircle aria-hidden weight="fill" />}
          {state.message}
        </p>
        <p aria-live="polite" className="sr-only">{spoken}</p>
        {error != null && lastCode !== 'DECLINED' && <ErrorNotice error={error} />}
        {state.canPay && <Button onClick={pay} busy={busy}>Pay {price}</Button>}
        {(state.kind === 'expired' || state.kind === 'ended') && (
          <Link to={`/events/${b.eventId}`} className="inline-flex min-h-11 items-center justify-center rounded-full border border-input-border px-5 hover:bg-muted">
            Pick new seats
          </Link>
        )}
      </Card>
    </div>
  );
}
