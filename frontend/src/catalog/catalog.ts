/** Customer catalog: types, search and filters. Availability always comes from the backend's live counts. */

export type Category = 'POP' | 'BOLLYWOOD' | 'ROCK' | 'EDM' | 'HIP_HOP' | 'FESTIVAL' | 'CLASSICAL';

export interface EventView {
  id: number;
  name: string;
  venue: string;
  startsAt: string;
  saleOpensAt: string;
  saleClosesAt: string;
  priceCents: number;
  totalSeats: number;
  availableSeats: number;
  listing?: 'CATALOG' | 'INTERNAL';
  artist?: string | null;
  category?: Category | null;
  city?: string | null;
  description?: string | null;
}

export const CATEGORIES: { id: Category | 'ALL'; label: string }[] = [
  { id: 'ALL', label: 'All Events' },
  { id: 'POP', label: 'Pop' },
  { id: 'BOLLYWOOD', label: 'Bollywood' },
  { id: 'ROCK', label: 'Rock' },
  { id: 'EDM', label: 'EDM' },
  { id: 'HIP_HOP', label: 'Hip-Hop' },
  { id: 'FESTIVAL', label: 'Festivals' },
  { id: 'CLASSICAL', label: 'Classical' },
];

export const categoryLabel = (c?: Category | null) => CATEGORIES.find((x) => x.id === c)?.label ?? 'Live event';

export interface Filters {
  q: string;
  category: Category | 'ALL';
  when: 'any' | '30d' | '90d' | 'later';
  price: 'any' | 'under50' | '50to100' | 'over100';
  availability: 'any' | 'onsale';
}

export const DEFAULT_FILTERS: Filters = { q: '', category: 'ALL', when: 'any', price: 'any', availability: 'any' };

export type SaleState = 'upcoming' | 'on-sale' | 'sold-out' | 'closed';

export function saleState(e: EventView, now: number): SaleState {
  if (now < Date.parse(e.saleOpensAt)) return 'upcoming';
  if (now >= Date.parse(e.saleClosesAt)) return 'closed';
  return e.availableSeats > 0 ? 'on-sale' : 'sold-out';
}

const DAY = 86_400_000;
const norm = (s: string) => s.normalize('NFKD').replace(/[̀-ͯ]/g, '').toLowerCase().trim();

export function matches(e: EventView, f: Filters, now: number): boolean {
  const q = norm(f.q);
  if (q && ![e.name, e.artist, e.venue, e.city].some((field) => field && norm(field).includes(q))) return false;
  if (f.category !== 'ALL' && e.category !== f.category) return false;
  const until = Date.parse(e.startsAt) - now;
  if (f.when === '30d' && until > 30 * DAY) return false;
  if (f.when === '90d' && until > 90 * DAY) return false;
  if (f.when === 'later' && until <= 90 * DAY) return false;
  const dollars = e.priceCents / 100;
  if (f.price === 'under50' && dollars >= 50) return false;
  if (f.price === '50to100' && (dollars < 50 || dollars > 100)) return false;
  if (f.price === 'over100' && dollars <= 100) return false;
  if (f.availability === 'onsale' && saleState(e, now) !== 'on-sale') return false;
  return true;
}

export const filterEvents = (list: EventView[], f: Filters, now: number) => list.filter((e) => matches(e, f, now));

/** The headline event: the priciest one actually on sale (deterministic, and never a sold-out or closed one). */
export function featured(list: EventView[], now: number): EventView | undefined {
  return list.filter((e) => saleState(e, now) === 'on-sale')
    .sort((a, b) => b.priceCents - a.priceCents || a.id - b.id)[0];
}

export function formatFrom(cents: number): string {
  return cents % 100 === 0 ? `$${cents / 100}` : `$${(cents / 100).toFixed(2)}`;
}

export function formatDate(iso: string, opts: Intl.DateTimeFormatOptions = { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric' }) {
  return new Date(iso).toLocaleDateString(undefined, opts);
}

export function formatTime(iso: string) {
  return new Date(iso).toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' });
}

export function place(e: Pick<EventView, 'venue' | 'city'>) {
  return e.city ? `${e.venue}, ${e.city}` : e.venue;
}
