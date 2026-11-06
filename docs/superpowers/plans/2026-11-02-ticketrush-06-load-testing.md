# TicketRush Plan 6 — Load Testing & Proof Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** prove the guarantees under a real crowd and record what this laptop measures. 1,000 users rush 100 seats; an abuser hits the rate limiter; declines and ambiguous provider timeouts happen; services are killed mid-sale. Afterwards a read-only invariant script shows no seat sold twice, no money lost and nothing stuck. The README reports the measured numbers.

**Architecture:** test tooling only, no service code. Everything lives under `load/`:
- k6 scripts run from the `grafana/k6:0.54.0` image on the Compose network (`ticketrush_default`), targeting `http://api-gateway:8080`;
- bash wrappers drive the scenarios;
- `invariants.sql` checks the data, and `invariants.sh` runs it as MySQL root inside the `mysql` container, then reads the payment reconciliation report with a service token minted by `openssl` (HS256, the same claims as `ServiceTokens`).

**Tech Stack:** k6 0.54.0 (Docker), bash (Git Bash on Windows), MySQL client inside the mysql container, openssl.

**Spec:** `docs/superpowers/specs/2026-11-01-ticketrush-design.md` §16.2 (S1–S5), §16.3 (metrics), §16.4 (invariants). **Roadmap:** plan 6. "Done when": S1 invariants pass on the Compose stack, and the numbers are recorded.

## Global Constraints

- No production code changes. The only exception is in `docker-compose.yml`: booking-service gains `TICKETRUSH_BOOKING_HOLDSECONDS: ${BOOKING_HOLD_SECONDS:-600}`, so S3 can use a 60 s hold. All other scenario knobs use the existing `PROVIDER_*` variables.
- Users are seeded via SQL: register one template user through the gateway, then `INSERT … SELECT` 1,000 users `load-<n>@example.com` copying its BCrypt hash (password `Load#12345`). The seeding is idempotent (`INSERT IGNORE`).
- k6 `setup()` logs users in at ≤ 15 req/s, because the gateway's default limiter keys anonymous calls by client IP at 20/s. Setup is not measured.
- Each run creates its own event through the admin API, with `saleOpensAt` = now + 30 s (computed in k6; the barrier is relative). Every VU sleeps until that instant.
- Bash only for load tooling: k6 runs in Docker, so there is no PowerShell twin. Wrappers live in `load/` and print a PASS/FAIL line per criterion.
- Results are written to `load/results/<scenario>-<timestamp>.json` (k6 `handleSummary`), which is git-ignored. Only the README table is kept.
- Test timestamps and comparisons use the DB clock (plan 3 lesson).
- No commits unless the user authorizes them.

## Review Focus

1. **The invariant script itself must be able to fail.** A corrupted row (a BOOKED seat whose booking is not CONFIRMED) must make it exit non-zero. Test in Task 1.
2. **Setup throttling.** If logins hit the gateway limiter, the run aborts with a clear message instead of running with missing tokens. Test in Task 1 (`setup` throws when < 99 % of tokens were obtained).
3. **S1 overselling.** Exactly 100 CONFIRMED, never more, and no seat in two active bookings. Checked by the invariants after S1.
4. **Ambiguous payments (S4).** Zero PROCESSING after the reconciler runs, and 0 report mismatches. The wrapper waits up to 120 s for reconciliation before checking.
5. **Chaos (S5).** After event-service, Kafka and one booking instance are stopped and restarted, the outbox drains to 0 and the invariants still hold.

---

## File Structure

```
load/
  README.md               how to run each scenario (short)
  lib.js                  k6 helpers: login batch, admin event creation, barrier, seat pick, book/pay/poll, summary writer
  s1-crowd.js             S1 (also S5 traffic)
  s2-abuser.js            S2
  s3-declines.js          S3 (retry pay ≤ 3x with new keys)
  s4-ambiguous.js         S4
  seed-users.sh           register template user, INSERT … SELECT 1,000 users
  run.sh                  run.sh <s1|s2|s3|s4|s5>: env/recreate containers, k6, invariants, restore
  chaos.sh                S5 background disruptions (stop event-service 20 s, Kafka 30 s, one booking replica)
  invariants.sql          spec §16.4 checks 1–5; each SELECT returns violating rows only
  invariants.sh           runs invariants.sql (+ optional --event <id>) and the reconciliation report; exit 1 on any violation
  results/.gitignore
docker-compose.yml        booking-service env: TICKETRUSH_BOOKING_HOLDSECONDS
README.md                 "Load test results" section with measured numbers + hardware
```

---

### Task 1: Tooling — seeding, k6 library, invariants (with a failing check)

**Interfaces:**
- `seed-users.sh [count=1000]`: idempotent; prints the number of load users present.
- `lib.js` exports:
  - `loginAll(n): string[]` (≤ 15/s, throws if fewer than 99 % succeed);
  - `adminCreateEvent(rows, perRow, opensInSec): {id, opensAt}`;
  - `waitUntil(ms)`;
  - `seats(eventId, token)`;
  - `book(token, eventId, seatId, key)`, `pay(token, bookingId, key)`, `pollBooking(token, id, untilStatuses, timeoutSec)`;
  - `summary(name)` (a `handleSummary` writing JSON plus a text summary).
- `invariants.sh [--event <id>]` → exit 0 only if every check returns 0 rows **and** the report has 0 mismatches.
- Checks in `invariants.sql` (spec §16.4):
  1. a seat in more than one active hold/booking;
  2. a BOOKED seat whose booking is not CONFIRMED, or a CONFIRMED booking with a seat not BOOKED;
  3. a CONFIRMED booking without exactly 1 SUCCEEDED/REFUND_* payment, or a booking with > 1 active payment;
  4. quota ≠ the sum of active seats, or quota > 4;
  5. (quiet period) PROCESSING payments, unpublished outbox rows, or HELD seats past `held_until` + 3 min.

  Cross-schema joins run as root (test tooling only, spec §16.4).

- [ ] **Step 1:** Write `seed-users.sh`, `lib.js`, `invariants.sql`, `invariants.sh`.
- [ ] **Step 2 (the check must fail):** on a running stack, create an event, make one booking via the API, then corrupt it (`UPDATE event_db.seats SET status='BOOKED' WHERE booking_id = <that booking>` while the booking is PENDING_PAYMENT). Run `invariants.sh --event <id>` → exits 1 and names check 2. Restore the seat. Run again → exit 0 for checks 1–4.
- [ ] **Step 3:** `seed-users.sh` twice → 1,000 load users both times (idempotent).

### Task 2: S1 Crowd + S2 Abuser

- [ ] `s1-crowd.js`:
  - 1,000 VUs (`per-vu-iterations`, 1 iteration, `maxDuration` 5 m); event 10×10.
  - Each VU, after the barrier: read the seat map → book a random AVAILABLE seat → on 409 refresh and retry (≤ 30 tries, 100–300 ms jitter) until it gets a seat or none are AVAILABLE → pay → poll until CONFIRMED (≤ 30 s).
  - Custom metrics:
    - `bookings_created` (counter);
    - `book_status{code}` (counter by status code);
    - `pay_to_confirmed` (trend);
    - `sold_out_seconds` (time from the barrier to the last CONFIRMED, set by the VU that observes sold-out: `gauge`).
  - Thresholds: `http_req_failed{name:book}` excludes 409/429 through `responseCallback`; 5xx share < 0.5 %.
- [ ] `run.sh s1`:
  - ensure the users are seeded;
  - run k6;
  - wait for a 60 s quiet period;
  - assert exactly 100 CONFIRMED bookings for the event (SQL);
  - run `invariants.sh --event <id>`;
  - capture: outbox lag (oldest unpublished age); the Redis filter effect (k6 409 count vs FAILED/`SEATS_UNAVAILABLE` booking rows for the event); and `hikaricp.connections.pending` max from booking's actuator during the run (sampled every 2 s by a background loop).
- [ ] `s2-abuser.js`: 10 VUs, each its own user, posting `POST /api/bookings` (event id 0 → 404 when admitted) every 50 ms for 30 s. A `checks` threshold: per user, 429s = requests − (2 × 30 + 5) ± 10 %, computed in `handleSummary` from per-user tagged counters.
- [ ] Run S1 and S2 and record the raw numbers in the ledger.

### Task 3: S3 Declines + S4 Ambiguous

- [ ] `s3-declines.js`: 300 VUs, a 10×10 event, the same flow as S1, but pay retries ≤ 3 times with new keys on 402. `run.sh s3` recreates `fake-payment-provider` with `PROVIDER_DECLINE_RATE=0.3` and booking-service with `BOOKING_HOLD_SECONDS=60`; after the run it restores both. Pass:
  - the invariants hold;
  - the provider's SUCCEEDED charges = local SUCCEEDED + REFUND_PENDING + REFUNDED (from the report: `providerSucceeded` and 0 mismatches).
- [ ] `s4-ambiguous.js`: 200 VUs, a 10×10 event; a pay that returns 202 polls `GET /api/payments/{id}` (≤ 90 s). `run.sh s4` uses `PROVIDER_TIMEOUT_AFTER_CHARGE_RATE=0.2`; after the run it waits until zero PROCESSING (≤ 120 s, polling SQL), then runs the invariants. Pass:
  - zero PROCESSING;
  - 0 mismatches;
  - no booking with more than one provider charge (no payment shares a booking with another SUCCEEDED, REFUND_PENDING or REFUNDED payment).

### Task 4: S5 Chaos + README results

- [ ] `chaos.sh` (started in the background by `run.sh s5`, which runs `s1-crowd.js` on a fresh event):
  - at barrier + 5 s, stop event-service for 20 s;
  - at + 30 s, stop kafka for 30 s;
  - at + 70 s, `docker compose kill` booking-service replica 2, then `docker compose up -d booking-service` to restore 2 replicas.
- [ ] `run.sh s5` waits until every service is healthy, then for up to 180 s for the outbox to drain to 0 (both schemas) and for no HELD seats past expiry. Then it runs the invariants. Pass: invariants hold; outbox 0. CONFIRMED may be < 100 (chaos may expire some holds), but never > 100.
- [ ] README "Load test results": a table per scenario (VUs, duration, req/s, `POST /api/bookings` p50/p95/p99, response mix, sold-out seconds, pay→confirmed p95, Redis filter %, outbox lag, Hikari pending max, pass/fail), the hardware (CPU, RAM, Docker Desktop memory), and how to reproduce. Numbers are copied from the runs; nothing is estimated.
- [ ] Stack stopped → `./mvnw verify` still green (no service code changed) → roadmap row 6 Done.
