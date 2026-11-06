import { describe, expect, it } from 'vitest';
import { attribute, seatState, stageMark } from '../demo/attribution';
import { classifyLosers } from '../demo/classify';
import { parseGroups, parseLockLines, parseLockOwner, parseMysqlBatch, hexToUuid } from '../demo/parse';
import { summarize } from '../demo/stats';
import type { Attempt, Snapshot } from '../demo/types';

describe('parsers for the read-only observer', () => {
  it('reads mysql -B output, with \\N as null and named columns', () => {
    const rows = parseMysqlBatch('5\tA1\tHELD\t0195ABCD\n6\tA2\tAVAILABLE\t\\N\n', ['id', 'seatNo', 'status', 'bookingHex']);
    expect(rows).toEqual([
      { id: '5', seatNo: 'A1', status: 'HELD', bookingHex: '0195ABCD' },
      { id: '6', seatNo: 'A2', status: 'AVAILABLE', bookingHex: null },
    ]);
    expect(parseMysqlBatch('', ['id'])).toEqual([]);
    // mysql -N -B prints SQL NULL as the literal NULL (seen on the real stack), not only \N
    expect(parseMysqlBatch('6\tNULL\tNULL\n', ['id', 'bookingHex', 'heldUntil'])).toEqual([{ id: '6', bookingHex: null, heldUntil: null }]);
  });

  it('turns HEX(BINARY(16)) into the UUID string the API returns', () => {
    expect(hexToUuid('0195F3A2B4C47D1E8F001122AABBCCDD')).toBe('0195f3a2-b4c4-7d1e-8f00-1122aabbccdd');
    expect(hexToUuid(null)).toBeNull();
  });

  it('splits a seat-lock owner token "userId:idempotencyKey"', () => {
    expect(parseLockOwner('42:race-a-1')).toEqual({ userId: 42, key: 'race-a-1' });
    expect(parseLockOwner('42:with:colons')).toEqual({ userId: 42, key: 'with:colons' });
    expect(parseLockOwner('garbage')).toBeNull();
  });
});

describe('load-test summary', () => {
  it('counts statuses and reports nearest-rank percentiles', () => {
    const lat = Array.from({ length: 100 }, (_, i) => i + 1);   // 1..100 ms
    const s = summarize(lat.map((ms, i) => ({ status: i < 20 ? 201 : 409, ms })));
    expect(s.total).toBe(100);
    expect(s.byStatus).toEqual({ 201: 20, 409: 80 });
    expect([s.p50, s.p95, s.p99, s.max]).toEqual([50, 95, 99, 100]);
  });

  it('handles no samples', () => {
    expect(summarize([])).toMatchObject({ total: 0, p50: null, max: null });
  });
});

const KEY_A = 'race-a';
const KEY_B = 'race-b';
const BOOKING_A = '0195f3a2-b4c4-7d1e-8f00-1122aabbccdd';
const BOOKING_B = '0195f3a2-b4c4-7d1e-8f00-1122aabbccee';
const attemptA: Attempt = { buyer: 'A', userId: 1, idempotencyKey: KEY_A, seatId: 10, sentAt: 1000, receivedAt: 1040, status: 201, bookingId: BOOKING_A };
const attemptB: Attempt = { buyer: 'B', userId: 2, idempotencyKey: KEY_B, seatId: 10, sentAt: 1000, receivedAt: 1012, status: 409, code: 'SEATS_UNAVAILABLE' };

function snapshot(over: Partial<Snapshot> = {}): Snapshot {
  return {
    startedAt: 1900,
    takenAt: 2000,
    readers: { db: true, redis: true },
    locks: [{ seatId: 10, ownerUserId: 1, ownerKey: KEY_A, pttlMs: 659_000 }],
    seats: [{ id: 10, seatNo: 'A1', status: 'HELD', bookingId: BOOKING_A, heldUntil: '2026-11-03 10:10:00.000' }],
    bookings: [{ id: BOOKING_A, userId: 1, idempotencyKey: KEY_A, status: 'PENDING_PAYMENT', failureReason: null, createdAt: '', updatedAt: '' }],
    payments: [],
    ...over,
  };
}

describe('stage attribution: only what the backend confirms', () => {
  it('winner: Redis key owned by this request, MySQL seat row points at its booking', () => {
    const s = attribute(attemptA, snapshot());
    expect(s.redis.state).toBe('acquired');
    expect(s.mysql.state).toBe('won');
    expect(s.booking.state).toBe('PENDING_PAYMENT');
  });

  it('Redis loser: 409 and no booking row, key owned by the other buyer, MySQL never reached', () => {
    const s = attribute(attemptB, snapshot());
    expect(s.redis.state).toBe('rejected');
    expect(s.redis.detail).toContain('user 1');
    expect(s.mysql.state).toBe('not-reached');
    expect(s.booking.state).toBe('none');
  });

  it('MySQL loser: passed the Redis filter but its booking FAILED with SEATS_UNAVAILABLE', () => {
    const s = attribute(attemptB, snapshot({
      locks: [],   // e.g. Redis down (filter fails open) or lock released
      bookings: [...snapshot().bookings,
        { id: BOOKING_B, userId: 2, idempotencyKey: KEY_B, status: 'FAILED', failureReason: 'SEATS_UNAVAILABLE', createdAt: '', updatedAt: '' }],
    }));
    expect(s.redis.state).toBe('not-blocked');   // passed OR failed open: the backend does not say which
    expect(stageMark(s.redis)).toBe('wait');       // grey, never a green check
    expect(s.redis.detail).toMatch(/failed open/);
    expect(s.mysql.state).toBe('rejected');
    expect(stageMark(s.mysql)).toBe('no');
  });

  it('green Redis mark only for an owner token that matches this request', () => {
    expect(stageMark(attribute(attemptA, snapshot()).redis)).toBe('ok');
    expect(stageMark(attribute(attemptB, snapshot()).redis)).toBe('no');
  });

  it('a failed MySQL reader never yields confident stages (C2)', () => {
    const s = attribute(attemptB, snapshot({ readers: { db: false, redis: true }, bookings: [], seats: [] }));
    expect(s.redis.state).toBe('unconfirmed');
    expect(s.mysql.state).toBe('unconfirmed');
    expect(s.redis.detail).toMatch(/MySQL read failed/);
  });

  it('a failed Redis reader cannot confirm a lock owner', () => {
    const s = attribute(attemptA, snapshot({ readers: { db: true, redis: false }, locks: [] }));
    expect(s.redis.state).toBe('unconfirmed');
    expect(s.mysql.state).toBe('won');   // MySQL evidence is still valid
  });

  it('reads that STARTED before the response are not evidence, even if they finished after it (I1)', () => {
    const s = attribute(attemptB, snapshot({ startedAt: 1005, takenAt: 1400 }));   // response at 1012
    expect(s.redis.state).toBe('unconfirmed');
  });

  it('never claims success without evidence: a snapshot taken before the response is unconfirmed', () => {
    const s = attribute(attemptA, snapshot({ startedAt: 1020, takenAt: 1030 }));
    expect(s.redis.state).toBe('unconfirmed');
    expect(s.mysql.state).toBe('unconfirmed');
  });

  it('a 201 whose seat row does not point at its booking is not shown as a MySQL win', () => {
    const s = attribute(attemptA, snapshot({ seats: [{ id: 10, seatNo: 'A1', status: 'AVAILABLE', bookingId: null, heldUntil: null }] }));
    expect(s.mysql.state).toBe('unconfirmed');
  });

  it('payment stage follows the payments row of the booking', () => {
    const s = attribute(attemptA, snapshot({
      payments: [{ id: 'p', bookingId: BOOKING_A, status: 'PROCESSING', failureReason: null, lookupAttempts: 2, updatedAt: '' }],
    }));
    expect(s.payment.state).toBe('PROCESSING');
    expect(s.payment.detail).toContain('2');
  });
});

describe('redis-cli and kafka-consumer-groups output', () => {
  it('reads the lock script reply (key, owner, PTTL triples) and an empty reply', () => {
    const out = '1) "bk:seatlock:27:10"\n2) "7:race-a"\n3) "59419"\n4) "bk:seatlock:27:3"\n5) "8:race-b"\n6) "1200"\n';
    expect(parseLockLines(out)).toEqual([
      { seatId: 3, ownerUserId: 8, ownerKey: 'race-b', pttlMs: 1200 },
      { seatId: 10, ownerUserId: 7, ownerKey: 'race-a', pttlMs: 59419 },
    ]);
    expect(parseLockLines('(empty array)\n')).toEqual([]);
  });

  it('sums consumer lag per group and topic', () => {
    const out = '\nGROUP          TOPIC             PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG  CONSUMER-ID HOST CLIENT-ID\n' +
      'event-service  booking.confirmed 0          10              12              2    c1 /1 x\n' +
      'event-service  booking.confirmed 1          5               5               0    c1 /1 x\n' +
      'booking-service payment.succeeded 0         3               3               -    -  -  -\n';
    expect(parseGroups(out)).toEqual([
      { group: 'event-service', topic: 'booking.confirmed', lag: 2 },
      { group: 'booking-service', topic: 'payment.succeeded', lag: 0 },
    ]);
  });
});

describe('seat state on the demo map', () => {
  const base = snapshot();
  it('mirrors event_db for AVAILABLE / HELD / BOOKED', () => {
    expect(seatState(10, base)).toBe('HELD');
    expect(seatState(10, snapshot({ seats: [{ ...base.seats[0], status: 'BOOKED' }] }))).toBe('BOOKED');
  });

  it('EXPIRED only once event_db has released the seat and its last booking expired', () => {
    const expiredBooking = { ...base.bookings[0], status: 'EXPIRED', seatIds: [10] };
    const released = { ...base.seats[0], status: 'AVAILABLE', bookingId: null, heldUntil: null };
    expect(seatState(10, snapshot({ bookings: [expiredBooking], seats: [released] }))).toBe('EXPIRED');
    // booking already EXPIRED but the seat release has not reached event_db yet: still HELD, never shown free early
    expect(seatState(10, snapshot({ bookings: [expiredBooking] }))).toBe('HELD');
  });

  it('a seat whose last booking failed or was never touched is simply AVAILABLE', () => {
    const released = { ...base.seats[0], status: 'AVAILABLE', bookingId: null, heldUntil: null };
    expect(seatState(10, snapshot({ seats: [released], bookings: [{ ...base.bookings[0], status: 'FAILED', seatIds: [10] }] }))).toBe('AVAILABLE');
    expect(seatState(99, base)).toBe('UNKNOWN');
  });
});

describe('load-test loser classification (I4): per request, never by subtraction', () => {
  const rows = [
    { userId: 1, key: 'k1', status: 'PENDING_PAYMENT', reason: null },
    { userId: 3, key: 'k3', status: 'FAILED', reason: 'SEATS_UNAVAILABLE' },
    { userId: 5, key: 'k5', status: 'FAILED', reason: 'HOLD_AMBIGUOUS' },
  ];
  it('409 without a row = Redis, 409 with FAILED/SEATS_UNAVAILABLE = MySQL, anything else is a flagged mismatch', () => {
    const c = classifyLosers([
      { userId: 1, key: 'k1', status: 201, code: undefined },
      { userId: 2, key: 'k2', status: 409, code: 'SEATS_UNAVAILABLE' },
      { userId: 3, key: 'k3', status: 409, code: 'SEATS_UNAVAILABLE' },
      { userId: 4, key: 'k4', status: 0, code: undefined },             // network error, no row
      { userId: 5, key: 'k5', status: 504, code: 'DEPENDENCY_UNAVAILABLE' },
      { userId: 6, key: 'k6', status: 409, code: 'SEATS_UNAVAILABLE' },
    ], [...rows, { userId: 6, key: 'k6', status: 'PENDING_PAYMENT', reason: null }]);
    expect(c.redisRejected).toBe(1);
    expect(c.mysqlRejected).toBe(1);
    expect(c.otherFailures).toBe(2);
    expect(c.mismatches).toEqual(['user 6: HTTP 409 but booking PENDING_PAYMENT']);
  });
});
