#!/usr/bin/env node
/**
 * Idempotent seed for the customer-facing demo catalog (listing = CATALOG).
 *
 * - Uses only the public admin API (POST /api/events) through the gateway; never touches SQL.
 * - Creates an event only when no CATALOG event with the same name exists, so re-running is a no-op.
 * - Never edits or deletes existing events, seats, bookings or users.
 * - Every listing is FICTIONAL demo content: invented dates, fictional venues, no claim of a real tour or real tickets.
 *
 * Usage (from frontend/):  npm run seed:catalog
 * Reads ADMIN_EMAIL / ADMIN_PASSWORD from the environment or ../.env. Never prints the token or the password.
 */
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const GATEWAY = process.env.DEMO_GATEWAY ?? 'http://localhost:8080';

function env(name) {
  if (process.env[name]) return process.env[name];
  const file = join(ROOT, '.env');
  if (existsSync(file)) {
    for (const line of readFileSync(file, 'utf8').split(/\r?\n/)) {
      if (line.trimStart().startsWith('#') || !line.includes('=')) continue;
      const k = line.slice(0, line.indexOf('=')).trim();
      if (k === name) return line.slice(line.indexOf('=') + 1).trim();
    }
  }
  throw new Error(`${name} is not set (environment or .env)`);
}

const DAY = 86_400_000;
/** Fictional dates relative to today, at 19:30 UTC-ish, so the catalog always looks upcoming. */
const at = (days, hourUtc = 14) => {
  const d = new Date(Date.now() + days * DAY);
  d.setUTCHours(hourUtc, 0, 0, 0);
  return d.toISOString();
};
// the Docker VM clock can lag the host: open sales a little in the past
const OPEN_NOW = new Date(Date.now() - 10 * 60_000).toISOString();

const CATALOG = [
  {
    name: 'Coldplay — Music of the Spheres World Tour', artist: 'Coldplay', category: 'POP',
    venue: 'Harbourline Stadium', city: 'Mumbai', days: 38, priceCents: 8900, rows: 14, seatsPerRow: 24,
    description: 'A stadium-sized night of light, colour and sing-along anthems. LED wristbands, confetti storms and a setlist spanning two decades of hits. Fictional demo listing.',
  },
  {
    name: 'Arijit Singh — Live in Concert', artist: 'Arijit Singh', category: 'BOLLYWOOD',
    venue: 'Lotus Dome Arena', city: 'New Delhi', days: 45, priceCents: 5500, rows: 12, seatsPerRow: 20,
    description: 'An intimate-meets-epic evening of soulful Bollywood ballads with a live orchestra and unplugged moments. Fictional demo listing.',
  },
  {
    name: 'Taylor Swift — The Eras Tour', artist: 'Taylor Swift', category: 'POP',
    venue: 'Skyline Park Stadium', city: 'Singapore', days: 60, priceCents: 14900, rows: 16, seatsPerRow: 25,
    description: 'A three-hour journey through every era, with surprise acoustic songs and a full theatrical stage production. Fictional demo listing.',
  },
  {
    name: 'Imagine Dragons — Loom World Tour', artist: 'Imagine Dragons', category: 'ROCK',
    venue: 'Riverside Bowl', city: 'Bengaluru', days: 52, priceCents: 7500, rows: 12, seatsPerRow: 22,
    description: 'Thunderous drums, arena-rock choruses and a light show built for the loudest crowd of the year. Fictional demo listing.',
  },
  {
    name: 'Diljit Dosanjh — Dil-Luminati Tour', artist: 'Diljit Dosanjh', category: 'HIP_HOP',
    venue: 'Golden Fields Ground', city: 'Chandigarh', days: 30, priceCents: 4900, rows: 14, seatsPerRow: 20,
    description: 'Punjabi pop and bhangra energy from the first beat to the last — big beats, bigger dance floor. Fictional demo listing.',
  },
  {
    name: 'The Weeknd — After Hours Experience', artist: 'The Weeknd', category: 'POP',
    venue: 'Neon Bay Arena', city: 'Dubai', days: 74, priceCents: 11900, rows: 12, seatsPerRow: 24,
    description: 'A cinematic, neon-soaked R&B spectacle with a dystopian city-skyline stage and late-night synths. Fictional demo listing.',
  },
  {
    name: 'Anuv Jain — India Tour', artist: 'Anuv Jain', category: 'BOLLYWOOD',
    venue: 'The Courtyard Amphitheatre', city: 'Pune', days: 24, priceCents: 2900, rows: 8, seatsPerRow: 16,
    description: 'Acoustic guitar, quiet lights and the songs everyone knows by heart — an unhurried evening of indie storytelling. Fictional demo listing.',
  },
  {
    name: 'Sunburn Festival — Electronic Music Festival', artist: 'Various artists', category: 'FESTIVAL',
    venue: 'Coastal Sands Festival Grounds', city: 'Goa', days: 88, priceCents: 6900, rows: 20, seatsPerRow: 30,
    description: 'Three stages, sunset-to-sunrise sets and a beachfront main stage. Multi-genre electronic line-up. Fictional demo listing.',
  },
  {
    name: 'Karan Aujla — It Was All A Dream Tour', artist: 'Karan Aujla', category: 'HIP_HOP',
    venue: 'Northgate Arena', city: 'Hyderabad', days: 66, priceCents: 5900, rows: 12, seatsPerRow: 20,
    description: 'Hard-hitting Punjabi hip-hop with a full live band, pyro and a crowd that knows every verse. Fictional demo listing.',
  },
  {
    name: 'Midnight Circuit — Warehouse Sessions', artist: 'Midnight Circuit', category: 'EDM',
    venue: 'Dock 9 Warehouse', city: 'Bengaluru', days: 19, priceCents: 3500, rows: 8, seatsPerRow: 18,
    description: 'Deep techno and melodic house in a converted dockside warehouse, with an immersive laser rig. Fictional demo listing.',
  },
  {
    name: 'Ragas at Dusk — An Evening of Hindustani Classical', artist: 'Vasant Iyer Ensemble', category: 'CLASSICAL',
    venue: 'Heritage Music Hall', city: 'Kolkata', days: 33, priceCents: 2500, rows: 10, seatsPerRow: 14,
    description: 'Sitar, sarod and tabla in conversation — evening ragas performed in a restored heritage hall. Fictional artists, fictional demo listing.',
  },
  {
    name: 'Stadium Echoes — Rock Revival Night', artist: 'The Velvet Static', category: 'ROCK',
    venue: 'Iron Bridge Arena', city: 'Chennai', days: 95, priceCents: 4500, rows: 10, seatsPerRow: 20,
    // a sale that has not opened yet, to show the "On sale soon" state honestly
    saleOpensInDays: 5,
    description: 'Big riffs and bigger choruses — a fictional rock line-up paying tribute to the stadium era. Fictional demo listing.',
  },
];

async function call(path, init = {}) {
  const r = await fetch(`${GATEWAY}${path}`, { ...init, headers: { 'Content-Type': 'application/json', Accept: 'application/json', ...init.headers } });
  const text = await r.text();
  const body = text ? JSON.parse(text) : {};
  if (!r.ok) throw new Error(`${init.method ?? 'GET'} ${path} → ${r.status} ${body.code ?? ''} ${body.detail ?? ''}`.trim());
  return body;
}

async function main() {
  const { accessToken } = await call('/api/users/login', { method: 'POST', body: JSON.stringify({ email: env('ADMIN_EMAIL'), password: env('ADMIN_PASSWORD') }) });
  const auth = { Authorization: `Bearer ${accessToken}` };
  const existing = await call('/api/events?page=0&size=50&listing=CATALOG');
  const have = new Set(existing.content.map((e) => e.name));
  let created = 0;
  for (const e of CATALOG) {
    if (have.has(e.name)) {
      console.log(`exists   ${e.name}`);
      continue;
    }
    const opens = e.saleOpensInDays ? at(e.saleOpensInDays, 6) : OPEN_NOW;
    const r = await call('/api/events', {
      method: 'POST', headers: auth,
      body: JSON.stringify({
        name: e.name, venue: e.venue, city: e.city, artist: e.artist, category: e.category, description: e.description,
        listing: 'CATALOG', startsAt: at(e.days), saleOpensAt: opens, priceCents: e.priceCents, rows: e.rows, seatsPerRow: e.seatsPerRow,
      }),
    });
    created++;
    console.log(`created  ${e.name} (id ${r.id}, ${r.totalSeats} seats)`);
  }
  console.log(`\n${created} created, ${CATALOG.length - created} already present. Existing events were not modified.`);
}

main().catch((e) => {
  console.error(`seed failed: ${e.message}`);
  process.exit(1);
});
