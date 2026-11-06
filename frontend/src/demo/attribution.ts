import type { Attempt, Snapshot, Stages } from './types';

/**
 * Which layer decided this booking request, using only evidence the backend left behind (plan 9).
 *
 * The client cannot tell: a Redis loser and a MySQL loser both get 409 SEATS_UNAVAILABLE. But booking-service only
 * inserts a booking row AFTER the Redis lock (acquire.lua) succeeds, and a MySQL rejection leaves that row FAILED
 * with reason SEATS_UNAVAILABLE. The lock value is the owner token `userId:idempotencyKey`, and event_db.seats
 * records which booking holds the seat. Anything without such evidence is "unconfirmed", never a success.
 */
export function attribute(a: Attempt, s: Snapshot): Stages {
  if (s.startedAt <= a.receivedAt) {   // a read that began before the response may predate the decision
    const wait = { state: 'unconfirmed', detail: 'waiting for a backend read that started after the response' };
    return { redis: wait, mysql: wait, booking: { state: 'unknown', detail: '' }, payment: { state: 'none', detail: '' } };
  }
  if (!s.readers.db) {   // without the MySQL read, the absence of a row proves nothing
    const wait = { state: 'unconfirmed', detail: 'MySQL read failed: no evidence' };
    return { redis: wait, mysql: wait, booking: { state: 'unknown', detail: 'MySQL read failed' }, payment: { state: 'unknown', detail: '' } };
  }
  const row = s.bookings.find((b) => b.userId === a.userId && b.idempotencyKey === a.idempotencyKey);
  const lock = s.locks.find((l) => l.seatId === a.seatId);
  const seat = s.seats.find((x) => x.id === a.seatId);
  const key = `bk:seatlock:…:${a.seatId}`;

  let redis: Stages['redis'];
  if (s.readers.redis && lock && lock.ownerUserId === a.userId && lock.ownerKey === a.idempotencyKey) {
    redis = { state: 'acquired', detail: `${key} is owned by this request (TTL ${Math.round(lock.pttlMs / 1000)} s)` };
  } else if (!row && a.status === 409 && a.code === 'SEATS_UNAVAILABLE') {
    redis = {
      state: 'rejected',
      detail: lock ? `no booking row was ever written; ${key} is now owned by user ${lock.ownerUserId}`
        : 'no booking row was written, so the request stopped at the Redis filter',
    };
  } else if (row && s.readers.redis) {
    // booking-service also lets requests through when Redis times out (fail-open): the stores cannot tell which
    redis = { state: 'not-blocked', detail: 'not blocked: a booking row exists but this request holds no lock now — the filter either passed it or failed open (see "Redis filter bypassed")' };
  } else if (!s.readers.redis) {
    redis = { state: 'unconfirmed', detail: 'Redis read failed: lock owner unknown' };
  } else {
    redis = { state: 'unconfirmed', detail: 'no lock or booking row found for this request' };
  }

  let mysql: Stages['mysql'];
  if (redis.state === 'rejected') {
    mysql = { state: 'not-reached', detail: 'the request never reached event-service' };
  } else if (row && seat && seat.bookingId === row.id && (seat.status === 'HELD' || seat.status === 'BOOKED')) {
    mysql = { state: 'won', detail: `seat ${seat.seatNo}: AVAILABLE → ${seat.status} for this booking (conditional UPDATE committed)` };
  } else if (row && row.status === 'FAILED' && row.failureReason === 'SEATS_UNAVAILABLE') {
    mysql = { state: 'rejected', detail: 'event-service refused: the conditional UPDATE matched 0 rows (seat not AVAILABLE)' };
  } else {
    mysql = { state: 'unconfirmed', detail: seat ? `seat ${seat.seatNo} is ${seat.status}${seat.bookingId ? ' for another booking' : ''}` : 'seat row not read' };
  }

  const booking = row
    ? { state: row.status, detail: row.failureReason ? `reason ${row.failureReason}` : '' }
    : { state: 'none', detail: 'no booking was created' };
  const pay = row ? s.payments.filter((p) => p.bookingId === row.id).at(-1) : undefined;
  const payment = pay
    ? { state: pay.status, detail: [pay.failureReason && `reason ${pay.failureReason}`, pay.lookupAttempts ? `${pay.lookupAttempts} reconciler lookup(s)` : ''].filter(Boolean).join(' · ') }
    : { state: 'none', detail: '' };
  return { redis, mysql, booking, payment };
}

export type Mark = 'ok' | 'no' | 'wait' | 'skip';

/** Green only for backend-confirmed success; "not blocked" and anything unconfirmed stay grey (review C1). */
export function stageMark(stage: { state: string }): Mark {
  switch (stage.state) {
    case 'acquired': case 'won': return 'ok';
    case 'rejected': return 'no';
    case 'not-reached': return 'skip';
    default: return 'wait';
  }
}

export type SeatState = 'AVAILABLE' | 'HELD' | 'BOOKED' | 'EXPIRED' | 'UNKNOWN';

/**
 * Demo map state. event_db.seats is the only authority on whether a seat is free: EXPIRED is shown only when
 * event_db has ALREADY released the seat (AVAILABLE) and the last booking that held it expired.
 */
export function seatState(seatId: number, s: Snapshot): SeatState {
  const seat = s.seats.find((x) => x.id === seatId);
  if (!seat) return 'UNKNOWN';
  if (seat.status !== 'AVAILABLE') return seat.status as SeatState;
  const last = s.bookings.filter((b) => b.seatIds?.includes(seatId)).at(-1);
  return last?.status === 'EXPIRED' ? 'EXPIRED' : 'AVAILABLE';
}
