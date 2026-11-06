import { CalendarBlank, Clock, Lightning, MapPin, Prohibit } from '@phosphor-icons/react';
import { Link } from 'react-router-dom';
import { Skeleton } from '../components/ui';
import { EventArt } from './EventArt';
import { categoryLabel, formatDate, formatFrom, formatTime, place, saleState, type EventView } from './catalog';

/** Sale status as icon + text, never colour alone. Derived from the backend's sale window and live seat count. */
export function SaleBadge({ event, now, className = '' }: { event: EventView; now: number; className?: string }) {
  const state = saleState(event, now);
  const look = {
    'on-sale': { icon: <Lightning aria-hidden weight="fill" size={14} />, text: 'On sale', tone: 'bg-success text-on-accent' },
    upcoming: { icon: <Clock aria-hidden size={14} />, text: `On sale ${formatDate(event.saleOpensAt, { day: 'numeric', month: 'short' })}`, tone: 'bg-warn text-on-accent' },
    'sold-out': { icon: <Prohibit aria-hidden size={14} />, text: 'Sold out', tone: 'bg-muted text-fg' },
    closed: { icon: <Prohibit aria-hidden size={14} />, text: 'Sales closed', tone: 'bg-muted text-fg' },
  }[state];
  return (
    <span className={`inline-flex items-center gap-1 rounded-full px-2.5 py-1 text-xs font-semibold ${look.tone} ${className}`}>
      {look.icon}{look.text}
    </span>
  );
}

/** "N of M seats left" with a bar, from the backend's live count. */
export function SeatsLeft({ event }: { event: Pick<EventView, 'availableSeats' | 'totalSeats'> }) {
  const pct = event.totalSeats ? Math.round((event.availableSeats / event.totalSeats) * 100) : 0;
  const low = event.availableSeats > 0 && pct <= 15;
  return (
    <div className="flex flex-col gap-1">
      <span className={`text-xs ${low ? 'font-semibold text-warn' : 'text-muted-fg'}`}>
        <span className="num">{event.availableSeats}</span> of <span className="num">{event.totalSeats}</span> seats left{low ? ' · selling fast' : ''}
      </span>
      <span aria-hidden className="h-1 overflow-hidden rounded-full bg-muted">
        <span className="bg-brand block h-full rounded-full" style={{ width: `${pct}%` }} />
      </span>
    </div>
  );
}

export function EventCard({ event, now, index = 0 }: { event: EventView; now: number; index?: number }) {
  const start = new Date(event.startsAt);
  return (
    <article
      className="group relative flex h-full flex-col overflow-hidden rounded-2xl border border-border bg-card transition duration-200 hover:-translate-y-1 hover:border-[color-mix(in_srgb,var(--accent)_55%,var(--border))] hover:shadow-[0_20px_50px_-20px_rgb(167_139_250/0.45)] motion-safe:animate-rise"
      style={{ animationDelay: `${Math.min(index, 8) * 40}ms` }}
    >
      <div className="relative aspect-[16/10] overflow-hidden">
        <EventArt seed={`${event.id}:${event.name}`} category={event.category} className="h-full w-full transition duration-500 group-hover:scale-105" />
        <div aria-hidden className="absolute inset-0 bg-gradient-to-t from-card via-transparent to-transparent" />
        <span className="absolute left-3 top-3 rounded-full bg-black/60 px-2.5 py-1 text-xs font-medium text-fg backdrop-blur">{categoryLabel(event.category)}</span>
        <SaleBadge event={event} now={now} className="absolute right-3 top-3" />
        <div className="absolute bottom-2 left-3 flex flex-col items-center rounded-xl bg-black/65 px-2.5 py-1 leading-tight backdrop-blur">
          <span className="text-[11px] font-semibold uppercase tracking-wider text-accent-2">{start.toLocaleDateString(undefined, { month: 'short' })}</span>
          <span className="num text-lg font-bold">{start.getDate()}</span>
        </div>
      </div>
      <div className="flex flex-1 flex-col gap-2 p-4">
        <h3 className="text-[17px] font-semibold leading-snug">
          {/* the whole card is the link target (stretched), the title is its accessible name */}
          <Link to={`/events/${event.id}`} className="after:absolute after:inset-0 after:content-[''] focus-visible:outline-none">
            {event.name}
          </Link>
        </h3>
        {event.artist && <p className="text-sm text-accent">{event.artist}</p>}
        <p className="flex items-start gap-1.5 text-sm text-muted-fg"><MapPin aria-hidden size={16} className="mt-0.5 shrink-0" />{place(event)}</p>
        <p className="flex items-center gap-1.5 text-sm text-muted-fg">
          <CalendarBlank aria-hidden size={16} className="shrink-0" />{formatDate(event.startsAt)} · {formatTime(event.startsAt)}
        </p>
        <div className="mt-auto flex items-end justify-between gap-3 pt-3">
          <SeatsLeft event={event} />
          <p className="shrink-0 text-right leading-tight">
            <span className="block text-[11px] uppercase tracking-wider text-muted-fg">From</span>
            <span className="num text-lg font-bold">{formatFrom(event.priceCents)}</span>
          </p>
        </div>
      </div>
      {/* keyboard focus ring for the stretched link */}
      <span aria-hidden className="pointer-events-none absolute inset-0 rounded-2xl ring-2 ring-transparent group-focus-within:ring-white" />
    </article>
  );
}

export function EventCardSkeleton() {
  return (
    <div className="overflow-hidden rounded-2xl border border-border bg-card" aria-hidden>
      <Skeleton className="aspect-[16/10] rounded-none" />
      <div className="flex flex-col gap-2 p-4">
        <Skeleton className="h-5 w-4/5" />
        <Skeleton className="h-4 w-1/3" />
        <Skeleton className="h-4 w-2/3" />
        <Skeleton className="h-4 w-1/2" />
        <Skeleton className="mt-3 h-6 w-full" />
      </div>
    </div>
  );
}

