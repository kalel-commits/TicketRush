import { randomUUID } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { classifyLosers } from '../src/demo/classify';
import { parseMysqlBatch } from '../src/demo/parse';
import { summarize, type Summary } from '../src/demo/stats';
import { mysql } from './docker';
import { REPO_ROOT } from './env';
import { buyers, createEvent, seats, timed } from './gateway';

export interface LoadParams { buyers: number; seats: number; pay: boolean }

export interface LoadResult {
  eventId: number;
  config: LoadParams;
  race: Summary & { wallMs: number; codes: Record<string, number> };
  evidence: { activeBookings: number; seatsTaken: number; mysqlRejected: number; redisRejected: number; otherFailures: number; mismatches: string[]; duplicateSeats: number };
  payments: { sent: number; httpCodes: Record<string, number>; confirmed: number; failed: number; processing: number; byStatus: Record<string, number>; settled: boolean } | null;
  invariants: { pass: boolean; violations: string[] };
}

type Progress = (phase: string, detail?: Record<string, unknown>) => void;

const clamp = (n: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, Math.trunc(n) || lo));

/**
 * N real buyer accounts, each with its own JWT, fire POST /api/bookings at the gateway in the same tick for S seats
 * (buyer i targets seat i mod S, so every seat is contested). Correctness is then checked in SQL, not inferred.
 */
export async function runLoad(params: LoadParams, progress: Progress): Promise<LoadResult> {
  const config = { buyers: clamp(params.buyers, 2, 200), seats: clamp(params.seats, 1, 50), pay: !!params.pay };
  const perRow = Math.min(config.seats, 10);
  const rows = Math.ceil(config.seats / perRow);
  progress('event', { message: `Creating a dedicated demo event with ${rows * perRow} seats` });
  const { id: eventId } = await createEvent(`Load test ${new Date().toISOString().slice(11, 19)} · ${config.buyers} buyers`, rows, perRow);

  progress('sessions', { message: `Signing in ${config.buyers} buyer accounts (rate-limited, not part of the measurement)` });
  const sessions = await buyers(Array.from({ length: config.buyers }, (_, i) => `load-${i}@demo.ticketrush.local`), 8,
    (done) => progress('sessions', { done, total: config.buyers }));
  const seatIds = (await seats(eventId, sessions[0].token)).map((s) => s.id).slice(0, config.seats);

  progress('race', { message: `${config.buyers} buyers → ${seatIds.length} seats, all at once` });
  const t0 = Date.now();
  const keys = sessions.map(() => `load-${randomUUID()}`);
  const results = await Promise.all(sessions.map((s, i) => timed<{ id?: string; code?: string }>('/api/bookings', {
    method: 'POST', token: s.token,
    headers: { 'Idempotency-Key': keys[i] },
    body: { eventId, seatIds: [seatIds[i % seatIds.length]] },
  })));
  const wallMs = Date.now() - t0;
  const codes: Record<string, number> = {};
  for (const r of results) if (r.status !== 201) codes[r.body.code ?? String(r.status)] = (codes[r.body.code ?? String(r.status)] ?? 0) + 1;
  const race = { ...summarize(results.map((r) => ({ status: r.status, ms: r.ms }))), wallMs, codes };
  progress('race-done', { race });

  progress('evidence', { message: 'Checking MySQL for what actually happened' });
  const evidence = await readEvidence(eventId, results.map((r, i) => ({ userId: sessions[i].userId, key: keys[i], status: r.status, code: r.body.code })));

  let payments: LoadResult['payments'] = null;
  if (config.pay) {
    const winners = results.map((r, i) => ({ r, s: sessions[i] })).filter(({ r }) => r.status === 201 && r.body.id);
    progress('pay', { message: `Paying for ${winners.length} winning bookings with the current provider mode` });
    const answers = await Promise.all(winners.map(({ r, s }) => timed<{ code?: string }>('/api/payments', {
      method: 'POST', token: s.token, headers: { 'Idempotency-Key': `pay-${randomUUID()}` }, body: { bookingId: r.body.id },
    })));
    const httpCodes: Record<string, number> = {};
    for (const a of answers) {
      const k = a.status === 0 ? 'network error' : `${a.status}${a.body.code ? ` ${a.body.code}` : ''}`;
      httpCodes[k] = (httpCodes[k] ?? 0) + 1;
    }
    payments = { ...await settlePayments(eventId, winners.length, progress), httpCodes };
  }

  progress('invariants', { message: 'Running the spec §16.4 invariant checks for this event' });
  const invariants = await runInvariants(eventId);
  return { eventId, config, race, evidence, payments, invariants };
}

async function readEvidence(eventId: number, sent: { userId: number; key: string; status: number; code?: string }[]) {
  const rowsOut = await mysql(`SELECT user_id, idempotency_key, status, failure_reason FROM booking_db.bookings WHERE event_id = ${eventId};`);
  const rows = parseMysqlBatch(rowsOut, ['userId', 'key', 'status', 'reason'])
    .map((r) => ({ userId: Number(r.userId), key: r.key!, status: r.status!, reason: r.reason }));
  const losers = classifyLosers(sent, rows);
  const out = await mysql(`
SELECT
 (SELECT COUNT(*) FROM booking_db.bookings WHERE event_id = ${eventId} AND status IN ('HOLDING','PENDING_PAYMENT','CONFIRMED')),
 (SELECT COUNT(*) FROM event_db.seats WHERE event_id = ${eventId} AND status IN ('HELD','BOOKED')),
 (SELECT COUNT(*) FROM booking_db.bookings WHERE event_id = ${eventId} AND status = 'FAILED' AND failure_reason = 'SEATS_UNAVAILABLE'),
 (SELECT COUNT(*) FROM (SELECT bs.seat_id FROM booking_db.booking_seats bs JOIN booking_db.bookings b ON b.id = bs.booking_id
   WHERE b.event_id = ${eventId} AND b.status IN ('HOLDING','PENDING_PAYMENT','CONFIRMED') GROUP BY bs.seat_id HAVING COUNT(*) > 1) d);`);
  const [activeBookings, seatsTaken, , duplicateSeats] = out.trim().split('\t').map(Number);
  return { activeBookings, seatsTaken, ...losers, duplicateSeats };
}

async function settlePayments(eventId: number, sent: number, progress: Progress) {
  const deadline = Date.now() + 75_000;   // an ambiguous charge waits for the 30 s reconciler
  for (;;) {
    const out = await mysql(`SELECT p.status, COUNT(*) FROM payment_db.payments p JOIN booking_db.bookings b ON b.id = p.booking_id
 WHERE b.event_id = ${eventId} GROUP BY p.status;
SELECT 'confirmed', COUNT(*) FROM booking_db.bookings WHERE event_id = ${eventId} AND status = 'CONFIRMED';`);
    const rows = Object.fromEntries(out.trim().split('\n').filter(Boolean).map((l) => l.split('\t')).map(([k, v]) => [k, Number(v)]));
    const { confirmed = 0, ...byStatus } = rows;
    const processing = byStatus.PROCESSING ?? 0;
    progress('pay', { byStatus, confirmed });
    const settled = processing === 0 && (byStatus.SUCCEEDED ?? 0) <= confirmed + (byStatus.REFUNDED ?? 0) + (byStatus.REFUND_PENDING ?? 0);
    if (settled || Date.now() > deadline) {
      return { sent, confirmed, failed: byStatus.FAILED ?? 0, processing, byStatus, settled };
    }
    await new Promise((r) => setTimeout(r, 1500));
  }
}

async function runInvariants(eventId: number) {
  const sql = readFileSync(join(REPO_ROOT, 'load', 'invariants.sql'), 'utf8');
  const out = await mysql(`SET @event = ${eventId}, @quiet = 0;\n${sql}`);
  const violations = out.split('\n').filter(Boolean).map((l) => l.replace(/\t/g, ' · '));
  return { pass: violations.length === 0, violations: violations.slice(0, 10) };
}
