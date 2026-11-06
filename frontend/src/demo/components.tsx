import { CheckCircle, Circle, CircleNotch, Database, Lightning, MinusCircle, Trophy, XCircle } from '@phosphor-icons/react';
import type { ReactNode } from 'react';
import { Button } from '../components/ui';
import { attribute, seatState, stageMark, type Mark, type SeatState } from './attribution';
import type { BuyerState } from './useBuyer';
import type { Attempt, Snapshot } from './types';

const time = (ms: number) => new Date(ms).toLocaleTimeString([], { hour12: false }) + '.' + String(ms % 1000).padStart(3, '0');
const short = (id: string | null | undefined) => (id ? id.slice(0, 8) : '—');

/* ------------------------------------------------------------------ seat map */

const SEAT_LOOK: Record<SeatState | 'SELECTED', { cls: string; label: string }> = {
  AVAILABLE: { cls: 'border-accent/60 bg-accent/10 text-fg hover:bg-accent/25', label: 'available' },
  HELD: { cls: 'border-warn bg-warn/25 text-fg', label: 'held' },
  BOOKED: { cls: 'border-danger bg-danger/30 text-fg', label: 'booked' },
  EXPIRED: { cls: 'border-info bg-info/20 text-fg', label: 'hold expired, released' },
  UNKNOWN: { cls: 'border-border bg-muted text-muted-fg', label: 'unknown' },
  SELECTED: { cls: 'border-fg bg-fg text-bg', label: 'selected' },
};

export function SeatLegend() {
  return (
    <ul aria-label="Seat states" className="flex flex-wrap gap-x-4 gap-y-1 text-xs text-muted-fg">
      {(['AVAILABLE', 'HELD', 'BOOKED', 'EXPIRED'] as const).map((s) => (
        <li key={s} className="flex items-center gap-1.5">
          <span aria-hidden className={`inline-block h-3 w-3 rounded-sm border ${SEAT_LOOK[s].cls}`} />
          {s}
          {s === 'EXPIRED' && <span className="text-muted-fg/80">(released, backend-confirmed)</span>}
        </li>
      ))}
    </ul>
  );
}

/**
 * The buyer's own view: statuses come from THIS buyer's polled GET /api/events/{id}/seats; EXPIRED is overlaid only
 * when the observer's event_db snapshot confirms the release.
 */
export function DemoSeatMap({ seats, snapshot, selected, onSelect, disabled }: {
  seats: { id: number; seatNo: string; status: string }[];
  snapshot: Snapshot | undefined;
  selected: number | null;
  onSelect: (id: number) => void;
  disabled?: boolean;
}) {
  return (
    <div className="grid grid-cols-8 gap-1.5">
      {seats.map((seat) => {
        let state = seat.status as SeatState;
        if (state === 'AVAILABLE' && snapshot && seatState(seat.id, snapshot) === 'EXPIRED') state = 'EXPIRED';
        const look = selected === seat.id && (state === 'AVAILABLE' || state === 'EXPIRED') ? SEAT_LOOK.SELECTED : SEAT_LOOK[state] ?? SEAT_LOOK.UNKNOWN;
        const free = state === 'AVAILABLE' || state === 'EXPIRED';
        return (
          <button
            key={seat.id}
            type="button"
            aria-label={`${seat.seatNo}, ${SEAT_LOOK[state]?.label ?? 'unknown'}`}
            aria-pressed={selected === seat.id}
            disabled={disabled || !free}
            onClick={() => onSelect(seat.id)}
            className={`num flex h-9 cursor-pointer items-center justify-center rounded border text-xs font-medium transition-all duration-300 disabled:cursor-not-allowed ${look.cls}`}
          >
            {seat.seatNo}
          </button>
        );
      })}
    </div>
  );
}

/* ------------------------------------------------------------------ buyer panel */

export function BuyerPanel({ label, tone, buyer, snapshot, selected, onSelect, priceCents }: {
  label: string; tone: string; buyer: BuyerState; snapshot: Snapshot | undefined;
  selected: number | null; onSelect: (id: number) => void; priceCents: number;
}) {
  const seatNo = buyer.seats.find((s) => s.id === (buyer.attempt?.seatId ?? selected))?.seatNo ?? '';
  const won = buyer.phase === 'won';
  const lost = buyer.phase === 'lost';
  const bookingStatus = buyer.booking?.status;
  const paymentStatus = buyer.payment?.status;
  const canPay = won && bookingStatus === 'PENDING_PAYMENT' && paymentStatus !== 'PROCESSING' && paymentStatus !== 'SUCCEEDED';
  return (
    <section aria-label={`Buyer ${label}`} className={`flex flex-col gap-3 rounded-lg border bg-card p-4 transition-colors duration-300 ${won ? 'border-accent' : lost ? 'border-danger' : 'border-border'}`}>
      <header className="flex items-center justify-between">
        <h3 className="flex items-center gap-2 font-semibold"><span aria-hidden className={`h-2.5 w-2.5 rounded-full ${tone}`} />Buyer {label}</h3>
        <span className="text-xs text-muted-fg">own session · own JWT</span>
      </header>
      <DemoSeatMap seats={buyer.seats} snapshot={snapshot} selected={selected} onSelect={onSelect} disabled={buyer.phase === 'booking'} />
      <div aria-live="polite" className="min-h-[3.5rem] rounded-md bg-bg/60 p-3 text-sm">
        {buyer.phase === 'idle' && <span className="text-muted-fg">{selected ? 'Ready to book.' : 'Pick a seat.'}</span>}
        {buyer.phase === 'booking' && <span className="flex items-center gap-2"><CircleNotch className="animate-spin" aria-hidden />Sending POST /api/bookings…</span>}
        {won && (
          <div className="flex flex-col gap-1 motion-safe:animate-[pop_.35s_ease-out]">
            <span className="flex items-center gap-2 font-semibold text-accent"><Trophy aria-hidden weight="fill" />You got seat {seatNo}</span>
            <span className="text-muted-fg">HTTP 201 in <span className="num">{buyer.attempt!.receivedAt - buyer.attempt!.sentAt} ms</span> · booking <span className="num">{short(buyer.attempt!.bookingId)}</span> · {bookingStatus ?? '…'}</span>
            {buyer.payment && <span>Payment: <strong>{paymentStatus}</strong>{buyer.payment.failureReason ? ` (${buyer.payment.failureReason})` : ''}</span>}
            {bookingStatus === 'CONFIRMED' && <span className="flex items-center gap-2 text-accent"><CheckCircle aria-hidden weight="fill" />Booking CONFIRMED</span>}
            {buyer.payError && <span className="text-danger">Payment answer: {buyer.payError}</span>}
          </div>
        )}
        {lost && (
          <div className="flex flex-col gap-1 motion-safe:animate-[pop_.35s_ease-out]">
            <span className="flex items-center gap-2 font-semibold text-danger"><XCircle aria-hidden weight="fill" />Seat {seatNo} is no longer available</span>
            <span className="text-muted-fg">HTTP <span className="num">409 SEATS_UNAVAILABLE</span> in <span className="num">{buyer.attempt!.receivedAt - buyer.attempt!.sentAt} ms</span>. No seat, no charge.</span>
          </div>
        )}
        {buyer.phase === 'error' && <span className="text-danger">Request failed: {buyer.message}</span>}
      </div>
      {buyer.seatsError != null && (
        <p role="alert" className="text-sm text-danger">Seat map not updating: {buyer.seatsError instanceof Error ? buyer.seatsError.message : 'request failed'}</p>
      )}
      <div className="flex flex-wrap gap-2">
        <Button variant="secondary" disabled={!selected || buyer.phase === 'booking' || won} onClick={() => selected && buyer.book(selected)}>
          Book {selected ? buyer.seats.find((s) => s.id === selected)?.seatNo : 'seat'}
        </Button>
        {canPay && <Button busy={buyer.paying} onClick={buyer.pay}>Pay ${(priceCents / 100).toFixed(2)}</Button>}
      </div>
    </section>
  );
}

/* ------------------------------------------------------------------ mechanism timeline */

const MARK: Record<Mark, ReactNode> = {
  ok: <CheckCircle role="img" aria-label="confirmed" weight="fill" className="shrink-0 text-accent" />,
  no: <XCircle role="img" aria-label="rejected" weight="fill" className="shrink-0 text-danger" />,
  wait: <Circle role="img" aria-label="no evidence yet" className="shrink-0 text-muted-fg" />,
  skip: <MinusCircle role="img" aria-label="not reached" className="shrink-0 text-muted-fg" />,
};

function Row({ id, n, title, mark, children }: { id: string; n: number; title: string; mark: Mark; children: ReactNode }) {
  return (
    <li data-testid={id} className="flex items-start gap-2 py-1.5 text-sm transition-opacity duration-300">
      <span className="num w-5 shrink-0 text-muted-fg">{n}</span>
      <span className="mt-0.5">{MARK[mark]}</span>
      <span><span className="font-medium">{title}</span> <span className="text-muted-fg">— {children}</span></span>
    </li>
  );
}

/** Stages 1-8 for each buyer's last request. Every mark comes from the backend snapshot, never assumed. */
export function MechanismTimeline({ attempts, snapshot }: { attempts: Attempt[]; snapshot: Snapshot | undefined }) {
  return (
    <section aria-label="Behind the scenes" className="rounded-lg border border-border bg-card p-4">
      <h2 className="mb-1 flex items-center gap-2 text-lg font-semibold"><Lightning aria-hidden />Behind the scenes</h2>
      <p className="mb-3 text-sm text-muted-fg">
        Both buyers get the same <code>409</code> whether Redis or MySQL stopped them, so each step below is proven from
        the stores themselves: the Redis key's owner, the <code>event_db.seats</code> row, and whether a booking row exists.
        A grey circle means "no evidence yet", never "assumed OK".
      </p>
      {attempts.length === 0 && <p className="text-sm text-muted-fg">Run a race to see each request's path.</p>}
      <div className="grid gap-4 lg:grid-cols-2">
        {attempts.map((a) => {
          const s = snapshot ? attribute(a, snapshot) : null;
          const b = a.buyer;
          const redisMark: Mark = s ? stageMark(s.redis) : 'wait';
          const mysqlMark: Mark = s ? stageMark(s.mysql) : 'wait';
          const bookingRow = snapshot?.bookings.find((x) => x.userId === a.userId && x.idempotencyKey === a.idempotencyKey);
          const seat = snapshot?.seats.find((x) => x.id === a.seatId);
          const confirmed = bookingRow?.status === 'CONFIRMED';
          return (
            <div key={`${b}-${a.idempotencyKey}`} className="rounded-md border border-border p-3">
              <h3 className="mb-1 font-semibold">Buyer {b} · seat {seat?.seatNo ?? a.seatId}</h3>
              <ol>
                <Row id={`stage-${b}-sent`} n={1} title="Request sent" mark="ok">POST /api/bookings at <span className="num">{time(a.sentAt)}</span> (Idempotency-Key {a.idempotencyKey.slice(0, 14)}…)</Row>
                <Row id={`stage-${b}-redis`} n={2} title={s?.redis.state === 'acquired' ? 'Redis lock acquired' : s?.redis.state === 'rejected' ? 'Redis lock rejected' : s?.redis.state === 'not-blocked' ? 'Redis filter: not blocked' : 'Redis lock (acquire.lua)'} mark={redisMark}>
                  {s?.redis.detail ?? 'waiting for the observer'}
                </Row>
                <Row id={`stage-${b}-mysql`} n={3} title={s?.mysql.state === 'not-reached' ? 'MySQL: not reached' : 'MySQL conditional UPDATE'} mark={mysqlMark}>
                  {s?.mysql.detail ?? 'waiting for the observer'}
                </Row>
                <Row id={`stage-${b}-http`} n={4} title={a.status === 201 ? 'Seat acquired' : 'Conflict returned'} mark={a.status === 201 ? 'ok' : 'no'}>
                  HTTP <span className="num">{a.status}{a.code ? ` ${a.code}` : ''}</span> after <span className="num">{a.receivedAt - a.sentAt} ms</span>
                </Row>
                <Row id={`stage-${b}-booking`} n={5} title="Booking" mark={bookingRow ? (bookingRow.status === 'FAILED' || bookingRow.status === 'EXPIRED' ? 'no' : 'ok') : a.status === 201 ? 'wait' : 'skip'}>
                  {bookingRow ? `${bookingRow.status}${bookingRow.failureReason ? ` (${bookingRow.failureReason})` : ''}` : s?.booking.detail ?? '—'}
                </Row>
                <Row id={`stage-${b}-payment`} n={6} title="Payment" mark={s?.payment.state === 'SUCCEEDED' ? 'ok' : s?.payment.state === 'FAILED' ? 'no' : s?.payment.state === 'none' ? 'skip' : 'wait'}>
                  {s && s.payment.state !== 'none' ? `${s.payment.state}${s.payment.detail ? ` · ${s.payment.detail}` : ''}` : 'no payment yet'}
                </Row>
                <Row id={`stage-${b}-kafka`} n={7} title="Kafka propagation" mark={confirmed && seat?.status === 'BOOKED' ? 'ok' : confirmed ? 'wait' : 'skip'}>
                  {confirmed
                    ? seat?.status === 'BOOKED' ? 'payment.succeeded → booking CONFIRMED; booking.confirmed → seat BOOKED in event_db'
                      : 'booking CONFIRMED; waiting for event-service to consume booking.confirmed'
                    : 'starts after a successful payment'}
                </Row>
              </ol>
            </div>
          );
        })}
      </div>
    </section>
  );
}

/* ------------------------------------------------------------------ observer */

function Stat({ label, value, hint }: { label: string; value: ReactNode; hint?: string }) {
  return (
    <div className="rounded-md bg-bg/60 p-2" title={hint}>
      <div className="text-xs text-muted-fg">{label}</div>
      <div className="num text-base">{value}</div>
    </div>
  );
}

export function ObserverPanel({ snapshot, updatedAt, error }: { snapshot: Snapshot | undefined; updatedAt: number; error?: unknown }) {
  const s = snapshot;
  const counts = (st: string) => s?.seats.filter((x) => x.status === st).length ?? 0;
  return (
    <section aria-label="System observer" className="flex flex-col gap-3 rounded-lg border border-border bg-card p-4 text-sm">
      <header className="flex items-center justify-between">
        <h3 className="flex items-center gap-2 font-semibold"><Database aria-hidden />System observer</h3>
        <span className="text-xs text-muted-fg">{updatedAt ? `read ${time(updatedAt)}` : 'waiting…'}</span>
      </header>
      <p className="text-xs text-muted-fg">Read-only reads of the real Redis, MySQL and Kafka for this event. Ids shortened; no tokens or payloads.</p>
      {error != null && <p role="alert" className="text-danger">Observer unavailable: {error instanceof Error ? error.message : 'read failed'}. The values below are from {updatedAt ? time(updatedAt) : 'no successful read'} and may be stale.</p>}
      {s?.errors?.map((e) => <p key={e} role="alert" className="text-danger">{e}</p>)}
      <div>
        <h4 className="mb-1 text-xs font-semibold uppercase tracking-wide text-muted-fg">Redis · bk:seatlock:{'{event}'}:{'{seat}'}</h4>
        {s && s.locks.length === 0 && <p className="text-muted-fg">no seat locks held</p>}
        <ul className="space-y-0.5">
          {s?.locks.map((l) => (
            <li key={l.seatId} className="num">seat {s.seats.find((x) => x.id === l.seatId)?.seatNo ?? l.seatId} → user {l.ownerUserId} · TTL {Math.round(l.pttlMs / 1000)} s</li>
          ))}
        </ul>
      </div>
      <div>
        <h4 className="mb-1 text-xs font-semibold uppercase tracking-wide text-muted-fg">MySQL · event_db.seats</h4>
        <div className="grid grid-cols-3 gap-2">
          <Stat label="AVAILABLE" value={counts('AVAILABLE')} /><Stat label="HELD" value={counts('HELD')} /><Stat label="BOOKED" value={counts('BOOKED')} />
        </div>
      </div>
      <div>
        <h4 className="mb-1 text-xs font-semibold uppercase tracking-wide text-muted-fg">booking_db.bookings · payment_db.payments</h4>
        {s && s.bookings.length === 0 && <p className="text-muted-fg">no bookings yet</p>}
        <ul className="max-h-40 space-y-0.5 overflow-y-auto">
          {s?.bookings.map((b) => {
            const p = s.payments.filter((x) => x.bookingId === b.id).at(-1);
            return (
              <li key={b.id} className="num">
                {short(b.id)} · user {b.userId} · <strong>{b.status}</strong>{b.failureReason ? ` (${b.failureReason})` : ''}
                {p && <> · pay <strong>{p.status}</strong>{p.lookupAttempts ? ` · ${p.lookupAttempts} lookups` : ''}</>}
              </li>
            );
          })}
        </ul>
      </div>
      <div>
        <h4 className="mb-1 text-xs font-semibold uppercase tracking-wide text-muted-fg">Outbox → Kafka (all events)</h4>
        <div className="grid grid-cols-2 gap-2">
          <Stat label="booking outbox unpublished" value={s?.outbox?.booking.unpublished ?? '—'} hint="rows written in the same transaction as the state change, not yet sent" />
          <Stat label="payment outbox unpublished" value={s?.outbox?.payment.unpublished ?? '—'} />
          <Stat label="last publish lag (booking)" value={s?.outbox?.booking.lastPublishLagMs != null ? `${s.outbox.booking.lastPublishLagMs} ms` : '—'} />
          <Stat label="last publish lag (payment)" value={s?.outbox?.payment.lastPublishLagMs != null ? `${s.outbox.payment.lastPublishLagMs} ms` : '—'} />
        </div>
        {s?.consumed && (
          <p className="mt-1 text-xs text-muted-fg">
            consumed (deduped via processed_events): {Object.entries({ ...s.consumed.event, ...s.consumed.booking, ...s.consumed.payment })
              .map(([t, n]) => `${t} ${n}`).join(' · ') || 'none yet'}
          </p>
        )}
        {s?.kafkaLag && <p className="text-xs text-muted-fg">consumer lag: {s.kafkaLag.map((g) => `${g.group}/${g.topic} ${g.lag}`).join(' · ') || '0'} (refreshed every 10 s)</p>}
      </div>
      <div className="grid grid-cols-2 gap-2">
        <Stat label="Redis filter bypassed (fail-open)" value={s?.metrics?.seatlockBypassed ?? 'n/a'} hint="booking.seatlock.bypassed, both booking replicas" />
        <Stat label="payment reconciler actions" value={s?.metrics?.reconcilerLookups ?? 'n/a'} hint="reconciler.payments (every 30 s)" />
      </div>
    </section>
  );
}

export { time, short };
