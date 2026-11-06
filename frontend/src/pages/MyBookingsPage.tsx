import { ArrowRight, CalendarBlank, MapPin, Ticket } from '@phosphor-icons/react';
import { useQueries, useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import type { Booking } from '../booking/payState';
import { formatDate, formatTime, place, type EventView } from '../catalog/catalog';
import { EventArt } from '../catalog/EventArt';
import { ErrorNotice, Skeleton, StatusBadge } from '../components/ui';

export function MyBookingsPage() {
  const bookings = useQuery({ queryKey: ['my-bookings'], queryFn: () => api<{ content: Booking[] }>('/api/bookings/me?size=20') });
  const eventIds = [...new Set(bookings.data?.content.map((b) => b.eventId) ?? [])];
  // event details for display only; a failed lookup just falls back to "Event N"
  const events = useQueries({
    queries: eventIds.map((id) => ({ queryKey: ['event', id], queryFn: () => api<EventView>(`/api/events/${id}`), staleTime: 60_000, retry: false })),
  });
  const byId = new Map(events.flatMap((q) => (q.data ? [[q.data.id, q.data] as const] : [])));

  return (
    <div className="mx-auto flex max-w-4xl flex-col gap-6">
      <div>
        <p className="text-sm font-semibold uppercase tracking-widest text-accent-2">Your tickets</p>
        <h1 className="text-3xl font-bold tracking-tight">My bookings</h1>
      </div>
      {bookings.isLoading && (
        <ul className="flex flex-col gap-3" aria-label="Loading bookings">
          {Array.from({ length: 3 }, (_, i) => <li key={i}><Skeleton className="h-28 w-full rounded-2xl" /></li>)}
        </ul>
      )}
      <ErrorNotice error={bookings.error} />
      {bookings.data?.content.length === 0 && (
        <div className="flex flex-col items-center gap-3 rounded-2xl border border-dashed border-border bg-surface px-6 py-14 text-center">
          <span className="grid h-14 w-14 place-items-center rounded-full bg-muted"><Ticket aria-hidden size={26} /></span>
          <h2 className="text-lg font-semibold">No bookings yet</h2>
          <p className="text-muted-fg">When you hold seats for an event, they show up here.</p>
          <Link className="bg-brand inline-flex min-h-11 items-center gap-2 rounded-full px-5 font-semibold text-on-accent" to="/events">
            Browse events <ArrowRight aria-hidden size={16} />
          </Link>
        </div>
      )}
      <ul className="flex flex-col gap-3">
        {bookings.data?.content.map((b) => {
          const e = byId.get(b.eventId);
          return (
            <li key={b.id}>
              <article className="group relative flex overflow-hidden rounded-2xl border border-border bg-card transition hover:border-input-border">
                <div className="relative hidden w-40 shrink-0 sm:block">
                  <EventArt seed={`${b.eventId}:${e?.name ?? ''}`} category={e?.category} crowd={false} className="absolute inset-0 h-full w-full" />
                </div>
                <div className="flex min-w-0 flex-1 flex-col gap-1.5 p-4">
                  <div className="flex flex-wrap items-start justify-between gap-2">
                    <h2 className="min-w-0 font-semibold">
                      <Link to={`/bookings/${b.id}`} className="after:absolute after:inset-0 after:content-[''] hover:underline">
                        {e?.name ?? <>Event <span className="num">{b.eventId}</span></>}
                      </Link>
                    </h2>
                    <StatusBadge status={b.status} />
                  </div>
                  {e && (
                    <p className="flex flex-wrap gap-x-4 gap-y-1 text-sm text-muted-fg">
                      <span className="flex items-center gap-1.5"><CalendarBlank aria-hidden size={16} />{formatDate(e.startsAt)} · {formatTime(e.startsAt)}</span>
                      <span className="flex items-center gap-1.5"><MapPin aria-hidden size={16} />{place(e)}</span>
                    </p>
                  )}
                  <p className="text-sm">Seats <span className="num">{b.seatNos.join(', ') || '—'}</span></p>
                </div>
              </article>
            </li>
          );
        })}
      </ul>
    </div>
  );
}
