# Plan 9: Live flash-sale demo

**Goal:** a `/demo/flash-sale` page that *proves*, on the real stack, that two (or a hundred) buyers racing for the
same seats cannot double-book. It shows each buyer's view, the backend's own evidence for why the winner won, payment
outcomes, and an observer of Redis, MySQL, outbox and Kafka state.

**Ground rules (from the request):**
- Every number and status shown comes from the real backend: API responses, or read-only reads of the real Redis,
  MySQL and Kafka.
- Nothing is simulated in the browser.
- Updates come from **polling** (1–2 s), and the UI says so. The only push channel in the system is the assistant's
  SSE, so no WebSocket or SSE is claimed for the demo.
- No change to booking, event or payment concurrency logic, and no weaker auth. No volume resets. No commits.

## What the backend really does (inspected 2026-11-03)

| Stage | Real mechanism | Evidence the demo can read |
|---|---|---|
| Redis fast lock | `acquire.lua`: atomic all-or-nothing. Checks every `bk:seatlock:{event}:{seat}` for a different owner, then `SET … PX` on all of them. Re-entrant for the same owner `userId:idempotencyKey`. This is the multi-key equivalent of `SET NX PX`, not literally `SET NX EX`. | `GET` the key gives the owner; `PTTL` gives the TTL. A Redis loser leaves **no booking row**. |
| Redis failure | The lock **fails open**: a breaker or error lets the request through to MySQL (counter `booking.seatlock.bypassed`). | The metric, and a booking row for a request that never held the lock. |
| MySQL guard | event-service conditional transition AVAILABLE→HELD inside one transaction. | `event_db.seats.status/booking_id/held_until`. A MySQL loser leaves a `FAILED` booking with reason `SEATS_UNAVAILABLE`. |
| Client response | Both Redis and MySQL losers get **the same** `409 SEATS_UNAVAILABLE`. | So the layer that stopped a request is attributed from the DB and Redis evidence above, never guessed. |
| Booking | `HOLDING → PENDING_PAYMENT → CONFIRMED / EXPIRED / CANCELLED / FAILED`. | `booking_db.bookings` |
| Payment | `PROCESSING → SUCCEEDED / FAILED`, plus refunds. A reconciler runs every 30 s and looks up ambiguous charges. | `payment_db.payments` (status, failure_reason, lookup attempts), `/api/payments/{id}` |
| Kafka | Transactional outbox (`outbox_events.published_at`) → topics → consumers dedupe via `processed_events`. | Outbox lag and unpublished count; `processed_events` per topic; consumer-group lag. |
| Expiry | booking sweeper (15 s) → `booking.cancelled/expired` → event-service releases the seat. The seat reconciler runs every 60 s. Default hold is 600 s; `BOOKING_HOLD_SECONDS` overrides it. | seat back to AVAILABLE **only when `event_db` says so** |

## Architecture decision: a dev-only demo harness (Vite plugin)

The browser cannot, and must not, do the following:
- read Redis or MySQL;
- hold the admin password;
- drive a 100-user test from one session.

So `frontend/demo-harness/` is a Vite dev-server plugin serving `/__demo/*`. It runs only under `npm run dev` and is
bound to localhost. It:
- reads `../.env` server-side (admin login, MySQL root password) and **never returns secrets**;
- creates dedicated demo events and demo buyer accounts through the **public API** (gateway, JWT, rate limits all
  apply);
- logs buyers in and hands each panel its **own** JWT. Panels call the gateway directly with separate tokens, so they
  are independent sessions. Tokens are never rendered.
- runs the load test **server-side**: N buyers, each with its own JWT, fire simultaneously at the gateway, measuring
  real latency and status codes;
- observes state read-only through `docker compose exec`: `redis-cli GET/PTTL/SCAN`, `mysql -B` SELECTs on the demo
  event only, `kafka-consumer-groups --describe`, and `curl actuator/metrics` (internal ports are not published).
  Output is sanitized: short ids, no payloads, no tokens.
- switches the fake payment provider's behaviour through a new control endpoint (below).

The one backend change is to the **fake payment provider** (a test double, internal network only, not routed by the
gateway): it gets `POST /__control/behaviour` to set decline / timeout-after-charge / down at runtime.
- Its settings were already mutable at runtime for tests.
- Restarting it instead would wipe its in-memory charges, and the reconciler needs those to resolve an ambiguous
  charge.

## Tasks

1. **Fake provider control endpoint** + `ProviderControlIT` (RED→GREEN).
2. **Harness core:** env and secrets loader, gateway client, docker readers, sanitized parsers.
   - Unit tests: mysql `-B` parser, redis owner parser, stats (p50/p95/p99, counts), stage attribution (Redis loser vs
     MySQL loser vs winner).
3. **Per-session API client:** `api(path, { token })` overrides the global token and does not end the app session on
   a 401.
4. **Demo page:**
   - two buyer panels, each with its own session, seat map and Book/Pay;
   - "Race" button that fires both holds in the same tick;
   - seat map with AVAILABLE / HELD / BOOKED / EXPIRED (EXPIRED = a seat whose booking expired, shown only after
     `event_db` confirms the release);
   - per-request timeline with client timestamps plus backend evidence;
   - System Observer panel.
   - Vitest: one winner one loser, the loser cannot pay, stages show "unconfirmed" until evidence exists, tokens never
     appear in the DOM.
5. **Load test panel:** N buyers × S seats (default 100 × 20), warning banner, metrics, plus an SQL check run after
   the race (no seat with more than one active booking; invariants 1–4 for the event).
   - Optional "pay all winners", using the current provider mode.
6. **Payment scenarios:** succeed / decline / ambiguous timeout → reconciler → confirmed / provider down. The real
   states are polled and shown, along with reconciler lookups.
7. **Verification:** Playwright on the real stack (race: exactly one 201; load: at most S successes, 0 duplicates).
   Then `npm test`, `npm run build`, and a presentation walkthrough (`docs/demo-walkthrough.md`).

## Out of scope / honest limits

- Refund-after-late-success is covered by backend tests but hard to trigger live on demand. The demo shows it only if
  it happens.
- Hold expiry is live only if booking-service runs with a short hold, e.g. `BOOKING_HOLD_SECONDS=90`, documented in
  the walkthrough. `.env` is not touched.
- Redis-outage scene (stopping Redis shows MySQL alone still prevents double booking): optional. The gateway's rate
  limiter also uses Redis, so its behaviour is shown as observed, not assumed.
