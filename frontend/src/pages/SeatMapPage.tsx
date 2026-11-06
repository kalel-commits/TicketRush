import { ArrowLeft, Armchair, CalendarBlank, Check, Clock, Lock, MapPin, MicrophoneStage, Ticket } from '@phosphor-icons/react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useMemo, useRef, useState, type KeyboardEvent } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { api, ApiError } from '../api/client';
import { Button, Card, ErrorNotice, Skeleton } from '../components/ui';
import { categoryLabel, formatDate, formatTime, place, type EventView } from '../catalog/catalog';
import { EventArt } from '../catalog/EventArt';
import { SaleBadge } from '../catalog/components';
import { MAX_SEATS, moveFocus, reconcile, seatLabel, splitSeatNo, toggle, type Seat } from '../seatmap/selection';

/** Polled every 2 s while the tab is visible (spec §23.2); the hold call, not this view, is authoritative. */
export function seatsQuery(eventId: number) {
  return {
    queryKey: ['seats', eventId],
    queryFn: () => api<Seat[]>(`/api/events/${eventId}/seats`),
    refetchInterval: 2000 as const,
    refetchIntervalInBackground: false as const,
  };
}

function SeatIcon({ status, selected }: { status: string; selected: boolean }) {
  if (selected) return <Check aria-hidden weight="bold" size={14} />;
  if (status === 'HELD') return <Clock aria-hidden size={14} />;
  if (status === 'BOOKED') return <Lock aria-hidden size={14} />;
  return null;
}

export function SeatMapPage() {
  const eventId = Number(useParams().id);
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const event = useQuery({ queryKey: ['event', eventId], queryFn: () => api<EventView>(`/api/events/${eventId}`) });
  const seats = useQuery(seatsQuery(eventId));
  const [selected, setSelected] = useState<number[]>([]);
  const [focusIndex, setFocusIndex] = useState(0);
  const [notice, setNotice] = useState('');
  const [error, setError] = useState<unknown>(null);
  const [cooldown, setCooldown] = useState(0);   // seconds left of a 429's Retry-After (spec §23.2)
  useEffect(() => {
    if (cooldown <= 0) return;
    const t = setTimeout(() => setCooldown((c) => c - 1), 1000);
    return () => clearTimeout(t);
  }, [cooldown]);
  const [busy, setBusy] = useState(false);
  const inFlight = useRef(false);
  const attemptKey = useRef<string | null>(null);   // one Idempotency-Key per attempt, reused on retry
  const cells = useRef<(HTMLDivElement | null)[]>([]);
  const [, tick] = useState(0);

  // stable reference: a fresh [] per render would re-run the reconcile effect forever (infinite render loop)
  const list = useMemo(() => seats.data ?? [], [seats.data]);
  const columns = useMemo(() => {
    const firstRow = list.length ? splitSeatNo(list[0].seatNo).row : '';
    return Math.max(1, list.filter((s) => splitSeatNo(s.seatNo).row === firstRow).length);
  }, [list]);

  useEffect(() => {
    setSelected((sel) => {
      const kept = reconcile(sel, list);
      return kept.length === sel.length ? sel : kept;   // same state object when nothing changed: no re-render
    });
  }, [list]);
  useEffect(() => { attemptKey.current = null; }, [selected]);   // a new selection is a new attempt
  useEffect(() => { const t = setInterval(() => tick((n) => n + 1), 1000); return () => clearInterval(t); }, []);

  const ageSeconds = seats.dataUpdatedAt ? Math.floor((Date.now() - seats.dataUpdatedAt) / 1000) : 0;

  function select(seat: Seat) {
    const r = toggle(selected, seat);
    setSelected(r.selected);
    setNotice(r.refused ?? '');
  }

  function onKey(e: KeyboardEvent<HTMLDivElement>, index: number) {
    if (e.key === ' ' || e.key === 'Enter') {
      e.preventDefault();
      select(list[index]);
      return;
    }
    const next = moveFocus(index, e.key, columns, list.length);
    if (next !== index) {
      e.preventDefault();
      setFocusIndex(next);
      cells.current[next]?.focus();
    }
  }

  async function hold() {
    if (inFlight.current || selected.length === 0) return;   // a double click never sends a second request
    inFlight.current = true;
    setBusy(true);
    setError(null);
    attemptKey.current ??= crypto.randomUUID();
    try {
      const booking = await api<{ id: string }>('/api/bookings', {
        method: 'POST',
        body: { eventId, seatIds: selected },
        headers: { 'Idempotency-Key': attemptKey.current },
      });
      navigate(`/bookings/${booking.id}`);
    } catch (err) {
      setError(err);
      if (err instanceof ApiError && err.status === 429) setCooldown(err.retryAfter);
      if (err instanceof ApiError && (err.code === 'SEATS_UNAVAILABLE' || err.status === 409)) {
        await queryClient.invalidateQueries({ queryKey: ['seats', eventId] });
      }
    } finally {
      inFlight.current = false;
      setBusy(false);
    }
  }

  const e = event.data;
  const compact = columns > 16;   // big venues: smaller seats (30 px, above the 24 px target minimum) so the row fits
  const liveAvailable = seats.data ? list.filter((x) => x.status === 'AVAILABLE').length : e?.availableSeats;
  const total = e ? selected.length * e.priceCents : 0;
  const selectedNos = list.filter((x) => selected.includes(x.id)).map((x) => x.seatNo);

  function bookTickets() {
    const target = document.getElementById('seats');
    target?.scrollIntoView({ block: 'start' });
    (cells.current[focusIndex] ?? target)?.focus({ preventScroll: true });
  }

  return (
    <div className="flex flex-col gap-8">
      <Link to="/events" className="inline-flex min-h-11 w-fit items-center gap-1.5 rounded-full pr-3 text-sm text-muted-fg hover:text-fg">
        <ArrowLeft aria-hidden size={16} /> All events
      </Link>

      <section aria-labelledby="event-title" className="relative isolate -mt-4 overflow-hidden rounded-3xl border border-border">
        <EventArt seed={`${eventId}:${e?.name ?? ''}`} category={e?.category} className="absolute inset-0 -z-10 h-full w-full" />
        <div aria-hidden className="absolute inset-0 -z-10 bg-gradient-to-t from-[#05060d] via-[#05060dd9] to-[#05060d33]" />
        <div className="flex min-h-[340px] flex-col justify-end gap-4 p-6 sm:p-10">
          {e ? (
            <>
              <div className="flex flex-wrap items-center gap-2">
                <span className="rounded-full bg-black/60 px-3 py-1 text-xs font-medium backdrop-blur">{categoryLabel(e.category)}</span>
                {e.saleOpensAt && <SaleBadge event={{ ...e, availableSeats: liveAvailable ?? e.availableSeats }} now={Date.now()} />}
              </div>
              <h1 id="event-title" className="max-w-4xl text-3xl font-bold leading-tight tracking-tight sm:text-5xl">{e.name}</h1>
              {e.artist && <p className="text-lg text-accent">{e.artist}</p>}
            </>
          ) : (
            <h1 id="event-title" className="text-3xl font-bold">{event.isLoading ? 'Loading event…' : 'Seat map'}</h1>
          )}
        </div>
      </section>

      {e && (
        <div className="grid gap-6 lg:grid-cols-[1fr_340px]">
          <div className="flex flex-col gap-4">
            {e.description && <p className="max-w-3xl text-lg leading-relaxed text-[#d6dcea]">{e.description}</p>}
            <dl className="grid gap-3 sm:grid-cols-2">
              {e.startsAt && (
                <div className="flex gap-3 rounded-2xl border border-border bg-card p-4">
                  <CalendarBlank aria-hidden size={22} className="shrink-0 text-accent" />
                  <div><dt className="text-xs uppercase tracking-wider text-muted-fg">Date &amp; time</dt>
                    <dd className="font-medium">{formatDate(e.startsAt)} · {formatTime(e.startsAt)}</dd></div>
                </div>
              )}
              <div className="flex gap-3 rounded-2xl border border-border bg-card p-4">
                <MapPin aria-hidden size={22} className="shrink-0 text-accent" />
                <div><dt className="text-xs uppercase tracking-wider text-muted-fg">Venue</dt>
                  <dd className="font-medium">{e.venue ? place(e) : '—'}</dd></div>
              </div>
              {e.artist && (
                <div className="flex gap-3 rounded-2xl border border-border bg-card p-4">
                  <MicrophoneStage aria-hidden size={22} className="shrink-0 text-accent" />
                  <div><dt className="text-xs uppercase tracking-wider text-muted-fg">Artist</dt><dd className="font-medium">{e.artist}</dd></div>
                </div>
              )}
              <div className="flex gap-3 rounded-2xl border border-border bg-card p-4">
                <Armchair aria-hidden size={22} className="shrink-0 text-accent" />
                <div><dt className="text-xs uppercase tracking-wider text-muted-fg">Available seats</dt>
                  <dd className="font-medium">{liveAvailable != null ? <><span className="num">{liveAvailable}</span>{e.totalSeats ? <> of <span className="num">{e.totalSeats}</span></> : null} · live</> : '—'}</dd></div>
              </div>
            </dl>
          </div>
          <aside aria-label="Tickets" className="flex h-fit flex-col gap-3 rounded-2xl border border-border bg-card p-5">
            <p className="text-xs uppercase tracking-wider text-muted-fg">Tickets from</p>
            <p><span className="num text-3xl font-bold">${(e.priceCents / 100).toFixed(2)}</span> <span className="text-muted-fg">per seat</span></p>
            <p className="text-sm text-muted-fg">Up to {MAX_SEATS} seats per booking. Seats are held for you while you pay.</p>
            <button type="button" onClick={bookTickets}
                    className="bg-brand inline-flex min-h-12 cursor-pointer items-center justify-center gap-2 rounded-full px-6 font-semibold text-on-accent shadow-[0_10px_40px_-10px_rgb(244_114_182/0.7)] transition hover:brightness-110">
              <Ticket aria-hidden size={20} /> Book tickets
            </button>
          </aside>
        </div>
      )}

      <section id="seats" tabIndex={-1} aria-labelledby="seats-title" className="grid scroll-mt-24 gap-6 lg:grid-cols-[1fr_340px]">
        <Card className="min-w-0">
          <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
            <h2 id="seats-title" className="text-xl font-bold tracking-tight">Choose your seats</h2>
            <span className="text-sm text-muted-fg">Last updated <span className="num">{ageSeconds}</span>s ago
              {ageSeconds > 6 && <span className="ml-2 text-danger">Data may be stale</span>}</span>
          </div>
          <div className="mb-5 flex flex-wrap items-center gap-x-5 gap-y-2 text-sm text-muted-fg">
            <span className="flex items-center gap-1.5"><span className="inline-block h-4 w-4 rounded border border-accent bg-[color-mix(in_srgb,var(--accent)_14%,transparent)]" /> Available</span>
            <span className="flex items-center gap-1.5"><span className="bg-brand inline-grid h-4 w-4 place-items-center rounded text-on-accent"><Check aria-hidden size={10} weight="bold" /></span> Selected</span>
            <span className="flex items-center gap-1.5"><Clock aria-hidden size={14} className="text-warn" /> Held</span>
            <span className="flex items-center gap-1.5"><Lock aria-hidden size={14} /> Booked</span>
          </div>
          {seats.isLoading && <div className="grid grid-cols-8 gap-2" aria-label="Loading seats">{Array.from({ length: 24 }, (_, i) => <Skeleton key={i} className="h-11 w-11" />)}</div>}
          {seats.error && <ErrorNotice error={seats.error} />}
          <div className="overflow-x-auto pb-2">
            <div className="mx-auto w-max">
              {list.length > 0 && (
                <div aria-hidden className="mx-auto mb-6 flex h-9 w-4/5 items-center justify-center rounded-b-[40px] rounded-t-md bg-brand text-xs font-bold uppercase tracking-[0.3em] text-on-accent shadow-[0_12px_40px_-8px_rgb(167_139_250/0.7)]">
                  Stage
                </div>
              )}
              <div role="grid" aria-label="Seat map" aria-rowcount={Math.ceil(list.length / columns)}
                   className={`grid w-max ${compact ? 'gap-1' : 'gap-2'}`} style={{ gridTemplateColumns: `repeat(${columns}, ${compact ? '1.875rem' : '2.75rem'})` }}>
                {list.map((seat, i) => {
                  const isSelected = selected.includes(seat.id);
                  const available = seat.status === 'AVAILABLE';
                  return (
                    <div
                      key={seat.id}
                      ref={(el) => { cells.current[i] = el; }}
                      role="gridcell"
                      tabIndex={i === focusIndex ? 0 : -1}
                      aria-label={seatLabel(seat, isSelected)}
                      aria-selected={isSelected}
                      aria-disabled={!available && !isSelected}
                      onClick={() => { setFocusIndex(i); select(seat); }}
                      onKeyDown={(ev) => onKey(ev, i)}
                      className={`num flex ${compact ? 'h-[1.875rem] w-[1.875rem] rounded-md text-[10px]' : 'h-11 w-11 rounded-lg text-xs'} cursor-pointer select-none flex-col items-center justify-center border transition duration-150 ${
                        isSelected ? 'bg-brand scale-105 border-transparent font-semibold text-on-accent shadow-[0_6px_20px_-6px_rgb(244_114_182/0.8)]'
                          : available ? 'border-accent bg-[color-mix(in_srgb,var(--accent)_10%,transparent)] text-fg hover:bg-[color-mix(in_srgb,var(--accent)_28%,transparent)]'
                            : 'cursor-not-allowed border-border bg-muted text-muted-fg'}`}
                    >
                      <SeatIcon status={seat.status} selected={isSelected} />
                      {(!compact || (available && !isSelected)) && seat.seatNo}
                    </div>
                  );
                })}
              </div>
            </div>
          </div>
        </Card>

        <aside aria-label="Your selection" className="flex h-fit flex-col gap-3 rounded-2xl border border-border bg-card p-5 lg:sticky lg:top-24">
          <h3 className="text-lg font-semibold">Your selection</h3>
          <p aria-live="polite" className="text-sm">{selected.length} of {MAX_SEATS} seats selected</p>
          {selectedNos.length > 0 ? (
            <ul className="flex flex-wrap gap-2">
              {selectedNos.map((n) => <li key={n} className="num rounded-full border border-accent px-3 py-1 text-sm">{n}</li>)}
            </ul>
          ) : <p className="text-sm text-muted-fg">Pick seats on the map. Tap again to remove.</p>}
          {e && selected.length > 0 && (
            <p className="flex items-baseline justify-between border-t border-border pt-3">
              <span className="text-muted-fg">Total</span><span className="num text-2xl font-bold">${(total / 100).toFixed(2)}</span>
            </p>
          )}
          {notice && <p className="text-sm text-danger">{notice}</p>}
          <ErrorNotice error={error} />
          {cooldown > 0 && <p className="num text-sm text-muted-fg" aria-live="polite">{`Try again in ${cooldown} s.`}</p>}
          <Button onClick={hold} busy={busy} disabled={selected.length === 0 || cooldown > 0} className="w-full">
            Hold {selected.length} seat{selected.length === 1 ? '' : 's'}
          </Button>
          <p className="text-xs text-muted-fg">Holding reserves the seats for a limited time; you pay on the next step. Demo payments only.</p>
        </aside>
      </section>
    </div>
  );
}
