import { useQuery } from '@tanstack/react-query';
import { useCallback, useRef, useState } from 'react';
import { api, ApiError } from '../api/client';
import type { Booking, Payment } from '../booking/payState';
import type { DemoBuyer } from './harnessClient';
import type { Attempt } from './types';

export type BuyerPhase = 'idle' | 'booking' | 'won' | 'lost' | 'error';

/**
 * One demo buyer = one independent authenticated session: every call carries THIS buyer's own JWT
 * (api(..., { token })), never the app's login. Data comes from the real gateway, polled every 1 s.
 */
export function useBuyer(buyer: DemoBuyer | undefined, eventId: number | undefined) {
  const token = buyer?.token;
  const enabled = !!token && !!eventId;
  const seats = useQuery({
    queryKey: ['demo-seats', buyer?.label, eventId],
    queryFn: () => api<{ id: number; seatNo: string; status: string }[]>(`/api/events/${eventId}/seats`, { token }),
    enabled, refetchInterval: 1000, refetchIntervalInBackground: false,
  });
  const [phase, setPhase] = useState<BuyerPhase>('idle');
  const [attempt, setAttempt] = useState<Attempt | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [payment, setPayment] = useState<Payment | null>(null);
  const [payError, setPayError] = useState<string | null>(null);
  const [paying, setPaying] = useState(false);
  const inFlight = useRef(false);

  const booking = useQuery({
    queryKey: ['demo-booking', buyer?.label, attempt?.bookingId],
    queryFn: () => api<Booking>(`/api/bookings/${attempt!.bookingId}`, { token }),
    enabled: !!attempt?.bookingId && !!token,
    refetchInterval: (q) => (q.state.data && ['CONFIRMED', 'EXPIRED', 'CANCELLED', 'FAILED'].includes(q.state.data.status) ? false : 1000),
  });
  const paymentQuery = useQuery({
    queryKey: ['demo-payment', buyer?.label, payment?.id],
    queryFn: () => api<Payment>(`/api/payments/${payment!.id}`, { token }),
    enabled: !!payment && !!token,
    refetchInterval: (q) => ((q.state.data ?? payment)?.status === 'PROCESSING' ? 1000 : false),
  });

  /** Sends the hold immediately (the fetch starts in this tick), so two buyers raced together really overlap. */
  const book = useCallback(async (seatId: number) => {
    if (!buyer || !eventId || inFlight.current) return;
    inFlight.current = true;
    const key = `demo-${buyer.label}-${crypto.randomUUID()}`;
    setPhase('booking');
    setMessage(null);
    setPayment(null);
    setPayError(null);
    const sentAt = Date.now();
    try {
      const b = await api<Booking>('/api/bookings', { method: 'POST', token: buyer.token, headers: { 'Idempotency-Key': key }, body: { eventId, seatIds: [seatId] } });
      setAttempt({ buyer: buyer.label, userId: buyer.userId, idempotencyKey: key, seatId, sentAt, receivedAt: Date.now(), status: 201, bookingId: b.id });
      setPhase('won');
    } catch (e) {
      const status = e instanceof ApiError ? e.status : 0;
      const code = e instanceof ApiError ? e.code : 'NETWORK_ERROR';
      setAttempt({ buyer: buyer.label, userId: buyer.userId, idempotencyKey: key, seatId, sentAt, receivedAt: Date.now(), status, code });
      setPhase(code === 'SEATS_UNAVAILABLE' ? 'lost' : 'error');
      setMessage(e instanceof ApiError ? `${e.status} ${e.code}` : 'Network error');
    } finally {
      inFlight.current = false;
    }
  }, [buyer, eventId]);

  const pay = useCallback(async () => {
    if (!buyer || !attempt?.bookingId || paying) return;
    setPaying(true);
    setPayError(null);
    try {
      setPayment(await api<Payment>('/api/payments', { method: 'POST', token: buyer.token,
        headers: { 'Idempotency-Key': `demo-pay-${crypto.randomUUID()}` }, body: { bookingId: attempt.bookingId } }));
    } catch (e) {
      if (e instanceof ApiError && typeof e.body.paymentId === 'string') {
        setPayment({ id: e.body.paymentId, status: 'FAILED', failureReason: e.code });   // a stored failure, polled below
      }
      setPayError(e instanceof ApiError ? `${e.status} ${e.code}` : 'Network error');
    } finally {
      setPaying(false);
      void booking.refetch();
    }
  }, [buyer, attempt, paying, booking]);

  const reset = useCallback(() => {
    setPhase('idle'); setAttempt(null); setMessage(null); setPayment(null); setPayError(null);
  }, []);

  return {
    seats: seats.data ?? [], seatsError: seats.error, phase, attempt, message, book, pay, paying, reset,
    booking: booking.data ?? null, payment: paymentQuery.data ?? payment, payError,
  };
}

export type BuyerState = ReturnType<typeof useBuyer>;
