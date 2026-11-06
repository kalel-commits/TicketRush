import { ArrowDown, ArrowRight, ArrowsClockwise, CalendarBlank, Faders, MagnifyingGlass, MapPin, MusicNotesSimple, Ticket } from '@phosphor-icons/react';
import { useQuery } from '@tanstack/react-query';
import { useEffect, useMemo, useState } from 'react';
import { Link, useLocation, useSearchParams } from 'react-router-dom';
import { api } from '../api/client';
import { CATEGORIES, DEFAULT_FILTERS, featured, filterEvents, formatDate, formatFrom, formatTime, place, type EventView, type Filters } from '../catalog/catalog';
import { EventArt } from '../catalog/EventArt';
import { EventCard, EventCardSkeleton, SaleBadge, SeatsLeft } from '../catalog/components';
import { ErrorNotice, Skeleton } from '../components/ui';

function useNow(ms = 30_000) {
  const [now, setNow] = useState(Date.now());
  useEffect(() => { const t = setInterval(() => setNow(Date.now()), ms); return () => clearInterval(t); }, [ms]);
  return now;
}

/** Customer catalog only (listing=CATALOG): test, load-test and demo-race events never appear here. */
export const catalogQuery = {
  queryKey: ['events', 'catalog'],
  queryFn: () => api<{ content: EventView[] }>('/api/events?page=0&size=50&listing=CATALOG'),
  refetchInterval: 15_000,   // live seat counts; the seat map itself is the authoritative view
};

function filtersFrom(params: URLSearchParams): Filters {
  const pick = <T extends string>(key: string, allowed: readonly T[], fallback: T): T =>
    (allowed as readonly string[]).includes(params.get(key) ?? '') ? (params.get(key) as T) : fallback;
  return {
    q: params.get('q') ?? '',
    category: pick('category', CATEGORIES.map((c) => c.id), 'ALL'),
    when: pick('when', ['any', '30d', '90d', 'later'] as const, 'any'),
    price: pick('price', ['any', 'under50', '50to100', 'over100'] as const, 'any'),
    availability: pick('availability', ['any', 'onsale'] as const, 'any'),
  };
}

function Hero({ event, now }: { event: EventView; now: number }) {
  return (
    <section aria-labelledby="hero-title" className="relative isolate overflow-hidden rounded-3xl border border-border">
      <EventArt seed={`${event.id}:${event.name}`} category={event.category} className="absolute inset-0 -z-10 h-full w-full" />
      <div aria-hidden className="absolute inset-0 -z-10 bg-gradient-to-r from-[#05060d] via-[#05060dcc] to-transparent" />
      <div aria-hidden className="absolute inset-0 -z-10 bg-gradient-to-t from-[#05060d] via-transparent to-transparent" />
      <div className="flex min-h-[420px] max-w-2xl flex-col justify-end gap-4 p-6 sm:p-10 md:min-h-[480px]">
        <div className="flex flex-wrap items-center gap-2">
          <span className="bg-brand rounded-full px-3 py-1 text-xs font-bold uppercase tracking-wider text-on-accent">Featured</span>
          <SaleBadge event={event} now={now} />
        </div>
        <h2 id="hero-title" className="text-3xl font-bold leading-tight tracking-tight sm:text-5xl">{event.name}</h2>
        {event.description && <p className="max-w-xl text-base text-[#d6dcea] sm:text-lg">{event.description}</p>}
        <ul className="flex flex-wrap gap-x-5 gap-y-2 text-sm text-[#d6dcea]">
          <li className="flex items-center gap-1.5"><CalendarBlank aria-hidden size={18} />{formatDate(event.startsAt)} · {formatTime(event.startsAt)}</li>
          <li className="flex items-center gap-1.5"><MapPin aria-hidden size={18} />{place(event)}</li>
          <li className="flex items-center gap-1.5"><Ticket aria-hidden size={18} />From <span className="num font-semibold text-fg">{formatFrom(event.priceCents)}</span></li>
        </ul>
        <div className="max-w-xs"><SeatsLeft event={event} /></div>
        <div className="flex flex-wrap gap-3 pt-1">
          <Link to={`/events/${event.id}`} className="bg-brand inline-flex min-h-12 items-center gap-2 rounded-full px-6 font-semibold text-on-accent shadow-[0_10px_40px_-10px_rgb(244_114_182/0.7)] transition hover:brightness-110">
            Get tickets <ArrowRight aria-hidden size={18} />
          </Link>
          <a href="#browse" className="inline-flex min-h-12 items-center gap-2 rounded-full border border-white/30 bg-black/30 px-6 font-medium backdrop-blur transition hover:bg-black/50">
            Explore events <ArrowDown aria-hidden size={18} />
          </a>
        </div>
      </div>
    </section>
  );
}

function HeroSkeleton() {
  return <Skeleton className="min-h-[420px] w-full rounded-3xl md:min-h-[480px]" />;
}

const selectClass = 'min-h-11 w-full cursor-pointer rounded-full border border-input-border bg-surface px-4 text-[15px] text-fg';

export function EventsPage() {
  const now = useNow();
  const location = useLocation();
  const [params, setParams] = useSearchParams();
  const filters = filtersFrom(params);
  const events = useQuery(catalogQuery);
  const list = useMemo(() => events.data?.content ?? [], [events.data]);
  const results = useMemo(() => filterEvents(list, filters, now), [list, filters, now]);
  const hero = useMemo(() => featured(list, now), [list, now]);
  const filtering = JSON.stringify(filters) !== JSON.stringify(DEFAULT_FILTERS);

  // arriving from the nav search with #browse: bring the results into view once they exist
  useEffect(() => {
    if (location.hash === '#browse' && events.data) document.getElementById('browse')?.scrollIntoView({ block: 'start' });
  }, [location.hash, location.search, events.data]);

  function set(key: keyof Filters, value: string) {
    const next = new URLSearchParams(params);
    if (value === DEFAULT_FILTERS[key]) next.delete(key);
    else next.set(key, value);
    setParams(next, { replace: true });
  }

  return (
    <div className="flex flex-col gap-10">
      <h1 className="sr-only">Discover live events</h1>
      {events.isLoading ? <HeroSkeleton /> : hero && <Hero event={hero} now={now} />}

      <section id="browse" aria-labelledby="browse-title" className="flex scroll-mt-24 flex-col gap-5">
        <div className="flex flex-col gap-1">
          <p className="text-sm font-semibold uppercase tracking-widest text-accent-2">Discover</p>
          <h2 id="browse-title" className="text-2xl font-bold tracking-tight sm:text-3xl">Trending events</h2>
        </div>

        <nav aria-label="Categories" className="flex flex-wrap gap-2">
          {CATEGORIES.map((c) => {
            const active = filters.category === c.id;
            return (
              <button
                key={c.id} type="button" aria-pressed={active} onClick={() => set('category', c.id)}
                className={`inline-flex min-h-11 cursor-pointer items-center gap-1.5 rounded-full border px-4 text-sm font-medium transition ${
                  active ? 'bg-brand border-transparent text-on-accent' : 'border-border bg-surface text-muted-fg hover:border-input-border hover:text-fg'}`}
              >
                {c.id === 'ALL' && <MusicNotesSimple aria-hidden size={16} />}{c.label}
              </button>
            );
          })}
        </nav>

        <div className="grid gap-3 rounded-2xl border border-border bg-card p-4 sm:grid-cols-2 lg:grid-cols-[2fr_1fr_1fr_1fr]">
          <div className="flex flex-col gap-1 sm:col-span-2 lg:col-span-1">
            <label htmlFor="event-search" className="text-xs font-semibold uppercase tracking-wider text-muted-fg">Search</label>
            <div className="relative">
              <MagnifyingGlass aria-hidden size={18} className="pointer-events-none absolute left-3.5 top-1/2 -translate-y-1/2 text-muted-fg" />
              <input
                id="event-search" type="search" value={filters.q} onChange={(e) => set('q', e.target.value)}
                placeholder="Event, artist, venue or city"
                className="min-h-11 w-full rounded-full border border-input-border bg-surface pl-10 pr-4 text-[15px] text-fg placeholder:text-muted-fg"
              />
            </div>
          </div>
          <div className="flex flex-col gap-1">
            <label htmlFor="filter-when" className="text-xs font-semibold uppercase tracking-wider text-muted-fg">Date</label>
            <select id="filter-when" value={filters.when} onChange={(e) => set('when', e.target.value)} className={selectClass}>
              <option value="any">Any date</option>
              <option value="30d">Next 30 days</option>
              <option value="90d">Next 3 months</option>
              <option value="later">Later</option>
            </select>
          </div>
          <div className="flex flex-col gap-1">
            <label htmlFor="filter-price" className="text-xs font-semibold uppercase tracking-wider text-muted-fg">Price</label>
            <select id="filter-price" value={filters.price} onChange={(e) => set('price', e.target.value)} className={selectClass}>
              <option value="any">Any price</option>
              <option value="under50">Under $50</option>
              <option value="50to100">$50 – $100</option>
              <option value="over100">Over $100</option>
            </select>
          </div>
          <div className="flex flex-col gap-1">
            <label htmlFor="filter-availability" className="text-xs font-semibold uppercase tracking-wider text-muted-fg">Availability</label>
            <select id="filter-availability" value={filters.availability} onChange={(e) => set('availability', e.target.value)} className={selectClass}>
              <option value="any">All events</option>
              <option value="onsale">On sale now</option>
            </select>
          </div>
        </div>

        <div className="flex flex-wrap items-center justify-between gap-2 text-sm text-muted-fg">
          <p aria-live="polite">
            {events.data && <><span className="num font-semibold text-fg">{results.length}</span> {results.length === 1 ? 'event' : 'events'}{filtering ? ' match your filters' : ''}</>}
          </p>
          {filtering && (
            <button type="button" onClick={() => setParams(new URLSearchParams(), { replace: true })}
                    className="inline-flex min-h-11 cursor-pointer items-center gap-1.5 rounded-full px-3 text-accent hover:bg-muted">
              <Faders aria-hidden size={16} /> Clear filters
            </button>
          )}
        </div>

        {events.error && (
          <ErrorNotice error={events.error}>
            <button type="button" onClick={() => events.refetch()} className="mt-2 inline-flex min-h-11 cursor-pointer items-center gap-1.5 rounded-full border border-input-border px-4 hover:bg-muted">
              <ArrowsClockwise aria-hidden size={16} /> Try again
            </button>
          </ErrorNotice>
        )}

        {events.isLoading && (
          <ul className="grid gap-5 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4" aria-label="Loading events">
            {Array.from({ length: 8 }, (_, i) => <li key={i}><EventCardSkeleton /></li>)}
          </ul>
        )}

        {events.data && results.length === 0 && (
          <div className="flex flex-col items-center gap-3 rounded-2xl border border-dashed border-border bg-surface px-6 py-14 text-center">
            <span className="grid h-14 w-14 place-items-center rounded-full bg-muted"><MagnifyingGlass aria-hidden size={26} /></span>
            <h3 className="text-lg font-semibold">{list.length === 0 ? 'No events listed yet' : 'No events match your search'}</h3>
            <p className="max-w-md text-muted-fg">
              {list.length === 0
                ? 'The demo catalog is empty. Seed it with npm run seed:catalog in the frontend folder.'
                : 'Try a different artist, city or category, or clear the filters to see everything on sale.'}
            </p>
            {filtering && (
              <button type="button" onClick={() => setParams(new URLSearchParams(), { replace: true })}
                      className="bg-brand inline-flex min-h-11 cursor-pointer items-center rounded-full px-5 font-semibold text-on-accent">
                Clear filters
              </button>
            )}
          </div>
        )}

        {results.length > 0 && (
          <ul className="grid gap-5 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
            {results.map((e, i) => <li key={e.id}><EventCard event={e} now={now} index={i} /></li>)}
          </ul>
        )}
      </section>
    </div>
  );
}
