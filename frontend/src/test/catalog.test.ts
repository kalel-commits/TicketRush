import { describe, expect, it } from 'vitest';
import { DEFAULT_FILTERS, featured, filterEvents, formatFrom, saleState, type EventView } from '../catalog/catalog';

const NOW = Date.parse('2026-11-04T10:00:00Z');
const DAY = 86_400_000;

function ev(over: Partial<EventView>): EventView {
  return {
    id: 1, name: 'Event', venue: 'Hall', startsAt: new Date(NOW + 20 * DAY).toISOString(),
    saleOpensAt: new Date(NOW - DAY).toISOString(), saleClosesAt: new Date(NOW + 19 * DAY).toISOString(),
    priceCents: 5000, totalSeats: 100, availableSeats: 50, listing: 'CATALOG', ...over,
  };
}

const coldplay = ev({ id: 1, name: 'Coldplay — Music of the Spheres', artist: 'Coldplay', category: 'POP', venue: 'Harbourline Stadium', city: 'Mumbai', priceCents: 8900 });
const arijit = ev({ id: 2, name: 'Arijit Singh — Live', artist: 'Arijit Singh', category: 'BOLLYWOOD', venue: 'Lotus Dome Arena', city: 'New Delhi', priceCents: 5500, startsAt: new Date(NOW + 60 * DAY).toISOString() });
const ragas = ev({ id: 3, name: 'Ragas at Dusk', artist: 'Vasant Iyer Ensemble', category: 'CLASSICAL', venue: 'Heritage Music Hall', city: 'Kolkata', priceCents: 2500, availableSeats: 0 });
const soon = ev({ id: 4, name: 'Rock Revival', category: 'ROCK', city: 'Chennai', priceCents: 14900, saleOpensAt: new Date(NOW + 5 * DAY).toISOString(), startsAt: new Date(NOW + 120 * DAY).toISOString() });
const ALL = [coldplay, arijit, ragas, soon];
const ids = (list: EventView[]) => list.map((e) => e.id);

describe('catalog search and filters', () => {
  it('searches name, artist, venue and city, case-insensitively', () => {
    expect(ids(filterEvents(ALL, { ...DEFAULT_FILTERS, q: 'coldPLAY' }, NOW))).toEqual([1]);
    expect(ids(filterEvents(ALL, { ...DEFAULT_FILTERS, q: 'singh' }, NOW))).toEqual([2]);
    expect(ids(filterEvents(ALL, { ...DEFAULT_FILTERS, q: 'heritage' }, NOW))).toEqual([3]);
    expect(ids(filterEvents(ALL, { ...DEFAULT_FILTERS, q: ' new delhi ' }, NOW))).toEqual([2]);
    expect(filterEvents(ALL, { ...DEFAULT_FILTERS, q: 'nothing like this' }, NOW)).toEqual([]);
  });

  it('filters by category', () => {
    expect(ids(filterEvents(ALL, { ...DEFAULT_FILTERS, category: 'BOLLYWOOD' }, NOW))).toEqual([2]);
    expect(ids(filterEvents(ALL, { ...DEFAULT_FILTERS, category: 'ALL' }, NOW))).toEqual([1, 2, 3, 4]);
  });

  it('filters by date window, price range and availability', () => {
    expect(ids(filterEvents(ALL, { ...DEFAULT_FILTERS, when: '30d' }, NOW))).toEqual([1, 3]);
    expect(ids(filterEvents(ALL, { ...DEFAULT_FILTERS, when: 'later' }, NOW))).toEqual([4]);
    expect(ids(filterEvents(ALL, { ...DEFAULT_FILTERS, price: 'under50' }, NOW))).toEqual([3]);
    expect(ids(filterEvents(ALL, { ...DEFAULT_FILTERS, price: '50to100' }, NOW))).toEqual([1, 2]);
    expect(ids(filterEvents(ALL, { ...DEFAULT_FILTERS, price: 'over100' }, NOW))).toEqual([4]);
    // availability uses the backend's live seat count and sale window, never a made-up number
    expect(ids(filterEvents(ALL, { ...DEFAULT_FILTERS, availability: 'onsale' }, NOW))).toEqual([1, 2]);
  });

  it('derives sale state from the sale window and the live seat count', () => {
    expect(saleState(coldplay, NOW)).toBe('on-sale');
    expect(saleState(ragas, NOW)).toBe('sold-out');
    expect(saleState(soon, NOW)).toBe('upcoming');
    expect(saleState(ev({ saleClosesAt: new Date(NOW - 1).toISOString() }), NOW)).toBe('closed');
  });

  it('features the priciest event that is on sale, and nothing when none is', () => {
    expect(featured(ALL, NOW)?.id).toBe(1);
    expect(featured([ragas, soon], NOW)).toBeUndefined();
  });

  it('formats the starting price', () => {
    expect(formatFrom(8900)).toBe('$89');
    expect(formatFrom(2550)).toBe('$25.50');
  });
});
