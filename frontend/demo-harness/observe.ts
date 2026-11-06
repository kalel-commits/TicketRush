import { hexToUuid, parseGroups, parseLockLines, parseMysqlBatch } from '../src/demo/parse';
import type { BookingRow, LockRow, OutboxStats, PaymentRow, SeatRow, Snapshot } from '../src/demo/types';
import { kafkaGroups, metric, mysql, redisSeatLocks } from './docker';

/**
 * One read-only snapshot of the real stores for a demo event. Sanitized: ids, statuses, timestamps and counts only;
 * no payloads, no tokens. A reader that fails is reported in `errors` instead of looking like "nothing there".
 */
export async function snapshot(eventId: number): Promise<Snapshot> {
  const id = Math.trunc(eventId);
  if (!Number.isFinite(id) || id <= 0) throw new Error('bad event id');
  const errors: string[] = [];
  const startedAt = Date.now();
  let redisOk = true;
  const [db, locks] = await Promise.all([
    readDb(id).catch((e) => { errors.push(`MySQL: ${e.message}`); return null; }),
    readLocks(id).catch((e) => { errors.push(`Redis: ${e.message}`); redisOk = false; return [] as LockRow[]; }),
  ]);
  if (slowError) errors.push(slowError);
  return {
    startedAt,
    takenAt: Date.now(),
    readers: { db: db !== null, redis: redisOk },
    locks,
    seats: db?.seats ?? [],
    bookings: db?.bookings ?? [],
    payments: db?.payments ?? [],
    outbox: db?.outbox,
    consumed: db?.consumed,
    ...slow,
    errors: errors.length ? errors : undefined,
  };
}

async function readLocks(eventId: number): Promise<LockRow[]> {
  return parseLockLines(await redisSeatLocks(eventId));
}

const SQL = (e: number) => `
SELECT 'seat', s.id, s.seat_no, s.status, HEX(s.booking_id), s.held_until FROM event_db.seats s WHERE s.event_id = ${e} ORDER BY s.id;
SELECT 'bk', HEX(b.id), b.user_id, b.idempotency_key, b.status, b.failure_reason, b.created_at, b.updated_at
  FROM booking_db.bookings b WHERE b.event_id = ${e} ORDER BY b.created_at;
SELECT 'bs', HEX(bs.booking_id), bs.seat_id FROM booking_db.booking_seats bs JOIN booking_db.bookings b ON b.id = bs.booking_id WHERE b.event_id = ${e};
SELECT 'pay', HEX(p.id), HEX(p.booking_id), p.status, p.failure_reason, p.lookup_attempts, p.updated_at
  FROM payment_db.payments p JOIN booking_db.bookings b ON b.id = p.booking_id WHERE b.event_id = ${e} ORDER BY p.created_at;
SELECT 'ob', 'booking', SUM(published_at IS NULL), SUM(published_at IS NOT NULL),
  (SELECT TIMESTAMPDIFF(MICROSECOND, created_at, published_at) DIV 1000 FROM booking_db.outbox_events WHERE published_at IS NOT NULL ORDER BY published_at DESC LIMIT 1)
  FROM booking_db.outbox_events;
SELECT 'ob', 'payment', SUM(published_at IS NULL), SUM(published_at IS NOT NULL),
  (SELECT TIMESTAMPDIFF(MICROSECOND, created_at, published_at) DIV 1000 FROM payment_db.outbox_events WHERE published_at IS NOT NULL ORDER BY published_at DESC LIMIT 1)
  FROM payment_db.outbox_events;
SELECT 'pe', 'event', topic, COUNT(*) FROM event_db.processed_events GROUP BY topic;
SELECT 'pe', 'booking', topic, COUNT(*) FROM booking_db.processed_events GROUP BY topic;
SELECT 'pe', 'payment', topic, COUNT(*) FROM payment_db.processed_events GROUP BY topic;`;

async function readDb(eventId: number) {
  const lines = (await mysql(SQL(eventId))).split('\n').filter(Boolean);
  const tagged = (tag: string, cols: string[]) => parseMysqlBatch(lines.filter((l) => l.startsWith(`${tag}\t`)).join('\n'), ['tag', ...cols]);
  const seats: SeatRow[] = tagged('seat', ['id', 'seatNo', 'status', 'booking', 'heldUntil']).map((r) => ({
    id: Number(r.id), seatNo: r.seatNo!, status: r.status!, bookingId: hexToUuid(r.booking), heldUntil: r.heldUntil,
  }));
  const bookings: BookingRow[] = tagged('bk', ['id', 'userId', 'key', 'status', 'reason', 'created', 'updated']).map((r) => ({
    id: hexToUuid(r.id)!, userId: Number(r.userId), idempotencyKey: r.key!, status: r.status!, failureReason: r.reason, createdAt: r.created!, updatedAt: r.updated!,
  }));
  for (const r of tagged('bs', ['booking', 'seat'])) {
    const b = bookings.find((x) => x.id === hexToUuid(r.booking));
    if (b) (b.seatIds ??= []).push(Number(r.seat));
  }
  const payments: PaymentRow[] = tagged('pay', ['id', 'booking', 'status', 'reason', 'lookups', 'updated']).map((r) => ({
    id: hexToUuid(r.id)!, bookingId: hexToUuid(r.booking)!, status: r.status!, failureReason: r.reason, lookupAttempts: Number(r.lookups), updatedAt: r.updated!,
  }));
  const ob = Object.fromEntries(tagged('ob', ['svc', 'unpub', 'pub', 'lag']).map((r) => [r.svc, {
    unpublished: Number(r.unpub ?? 0) || 0, published: Number(r.pub ?? 0) || 0, lastPublishLagMs: r.lag == null ? null : Number(r.lag),
  } satisfies OutboxStats]));
  const consumed = { event: {} as Record<string, number>, booking: {} as Record<string, number>, payment: {} as Record<string, number> };
  for (const r of tagged('pe', ['svc', 'topic', 'n'])) consumed[r.svc as keyof typeof consumed][r.topic!] = Number(r.n);
  return { seats, bookings, payments, outbox: { booking: ob.booking, payment: ob.payment }, consumed };
}

/** Slow readers (a JVM CLI start for Kafka, actuator calls): refreshed in the background every 10 s. */
let slow: Pick<Snapshot, 'kafkaLag' | 'metrics'> = {};
let slowError: string | null = null;
let slowTimer: ReturnType<typeof setTimeout> | null = null;

/** Kafka CLI + actuator reads, one at a time (a JVM CLI can take seconds), 10 s after the previous one finished. */
export function startSlowReaders() {
  if (slowTimer) return;
  const refresh = async () => {
    let kafkaFailed = false;
    const [groups, bypassed, lookups] = await Promise.all([
      kafkaGroups().then(parseGroups).catch(() => { kafkaFailed = true; return undefined; }),
      metric('booking-service', 8083, 'booking.seatlock.bypassed'),
      metric('payment-service', 8085, 'reconciler.payments'),
    ]);
    slow = { kafkaLag: groups, metrics: { seatlockBypassed: bypassed, reconcilerLookups: lookups } };
    slowError = kafkaFailed ? 'Kafka: consumer-group read failed (lag unknown)' : null;
    slowTimer = setTimeout(() => void refresh(), 10_000);
    slowTimer.unref?.();
  };
  slowTimer = setTimeout(() => void refresh(), 0);
}
