# TicketRush

Flash-sale event ticketing: Java 21 + Spring Boot microservices that never sell a seat twice.
Design: `docs/superpowers/specs/2026-11-01-ticketrush-design.md` · Plans: `docs/superpowers/plans/`.

**Status:** Plans 1–9 are done: foundation, event, booking and payment services, reliability jobs, load tests, the AI assistant, the demo frontend, and the live flash-sale demo. Still open: the real-key assistant eval (needs `ANTHROPIC_API_KEY`) and load scenarios S4 and S5 (need more host memory). See the sections below for details.

**Services:** `api-gateway` · `discovery-server` (Eureka) · `user-service` · `event-service` · `booking-service` · `payment-service` (with `fake-payment-provider`) · `ai-assistant` · `frontend` (React client). Shared code lives in `common/`.

## Prerequisites

- Docker Desktop (running)
- JDK 21 (only for running tests/builds outside Docker)
- `openssl` (bundled with Git for Windows)

## Run it

```bash
cp .env.example .env                      # PowerShell: Copy-Item .env.example .env
bash infra/keys/generate-dev-keys.sh      # PowerShell: .\infra\keys\generate-dev-keys.ps1
docker compose up -d --build
bash infra/smoke/smoke.sh      # PowerShell: .\infra\smoke\smoke.ps1
```

Generate the keys **before** the first `docker compose up`. They go in `infra/keys/private/` (mounted
only into user-service) and `infra/keys/public/`. If a key is missing, the service stops with
`RSA key not found: <path>`. Run the script, then `docker compose up -d` again.

Everything goes through the gateway at `http://localhost:8080`; the Eureka dashboard is at
`http://localhost:8761`. Both are bound to `127.0.0.1` only: Eureka accepts unauthenticated registrations,
so it must not be reachable from your Wi-Fi. Service ports are deliberately **not** published.
Change the default admin credentials in `.env` for anything beyond local development.

```bash
curl -X POST localhost:8080/api/users/register -H 'Content-Type: application/json' \
     -d '{"email":"alice@example.com","password":"Secret#123"}'
curl -X POST localhost:8080/api/users/login -H 'Content-Type: application/json' \
     -d '{"email":"alice@example.com","password":"Secret#123"}'          # → accessToken
curl localhost:8080/api/users/me -H "Authorization: Bearer <accessToken>"
```

Swagger UI for user-service: services aren't reachable from the host, so for local browsing add a
`docker-compose.override.yml` that publishes `8081:8081`, then open `http://localhost:8081/swagger-ui.html`.

## Tests

```bash
./mvnw verify                             # Windows: .\mvnw.cmd verify   (needs Docker for Testcontainers)
bash infra/verify-infra.sh                # DB isolation proof, Redis, Kafka (stack must be up)
```

On a 16 GB laptop, run `docker compose stop` before `./mvnw verify`. Running the full Compose stack and the
Testcontainers suite at the same time can run out of native memory (it was observed crashing the gateway test JVM).

## Security model

| | User token | Service token |
|---|---|---|
| Algorithm | RS256 | HS256 |
| Signed by | user-service only (private key mounted only there) | event/booking/payment (`INTERNAL_JWT_SECRET`) |
| Verified by | gateway + every service (public key) | each service's `/internal/**` chain |
| `iss` / `aud` / `token_use` | `ticketrush-user-service` / `ticketrush-api` / `user` | `ticketrush-internal` / `ticketrush-internal` / `service` |
| Lifetime | 1 h | 5 min |

- The gateway holds only the public key: it can verify tokens but never sign one.
- `/internal/**` has three layers of protection: no gateway route plus `denyAll`, unpublished service ports, and service-token authentication in each service.
- Accepted trade-off: services share one internal secret, so a compromised service could impersonate another *service*, never a user or admin. The upgrade path is per-service keys or mTLS.
- Gateway rate limits: booking/payment writes 2 req/s (burst 5), assistant 10 req/min, everything else 20 req/s (burst 40). Limits are keyed by user id, or by socket address for anonymous callers, never by `X-Forwarded-For`.
  - If a reverse proxy is ever put in front of the gateway, trust `X-Forwarded-For` only from an explicit allow-list of proxy addresses.
  - On Docker Desktop, all host traffic arrives from one NAT address, so anonymous callers (login/register) share one bucket. Load tests should log users in before the measured phase.
- Each service has its own MySQL user, granted only on its own schema. `infra/verify-infra.sh` proves this.

## Load test results (plan 6)

The tooling is in `load/` (see `load/README.md`): k6 0.54 in Docker against the full Compose stack, then the
spec §16.4 invariant script plus the payment reconciliation report. All numbers below were **measured on one laptop**
running everything at once: k6, 2 booking replicas and 10 other containers.

**Hardware:** Intel Core i5-13420H (8 cores / 12 threads), 15.6 GB RAM, Windows 11, Docker Desktop with 12 CPUs and
7.6 GB.

| | S1 Crowd | S2 Abuser | S3 Declines |
|---|---|---|---|
| Setup | 1,000 users, 100 seats, 1 seat each | 10 users × ~20 req/s, 30 s | 300 users, 100 seats, 30 % declines, 60 s holds |
| Pass criteria (spec §16.2) | exactly 100 CONFIRMED + invariants | 429s per user ≈ sent − 65 (±10 %) | invariants + provider charges = SUCCEEDED + REFUNDED |
| **Result** | **PASS**: 100 CONFIRMED, invariants 1–5 hold, 0 report mismatches | **PASS**: every user ~535 sent / ~466 × 429 (expected ~470) | **PASS**: 125 charges = 85 SUCCEEDED + 40 DECLINED, 0 mismatches |
| Requests | 6,929 (52 req/s) | 5,352 (188 req/s) | 13,815 (176 req/s) |
| `POST /api/bookings` p50 / p95 / p99 | 876 / 2,811 / 4,498 ms | — | 283 / 2,249 / 5,691 ms |
| Booking responses | 201: 100, 409: 1,083, 5xx: 898 | — | 201: 89, 409: 5,831, 5xx: 506 |
| Time to sell out | 25.6 s | — | 12.4 s |
| Pay → CONFIRMED p95 | 4.3 s | — | 7.5 s |
| Redis filter (409s that never reached MySQL) | 100 % | — | — |
| Outbox lag max / Hikari pending max | 785 ms / 0 | — | — |

**S4 (ambiguous provider) and S5 (chaos) have not been measured yet.** The S4 run was stopped by the host running
out of memory, and S5 needs the same headroom. Run `bash load/run.sh s4` and `bash load/run.sh s5` on a machine
with more free memory. The invariant check after the interrupted S4 run still passed for all events.

### What the load tests changed

- **Outbox relay lock contention (real bug, fixed).** The relay kept its transaction open across the synchronous
  Kafka send. Under REPEATABLE READ, its `FOR UPDATE SKIP LOCKED` read gap-locked the "unpublished" index range, so
  every business transaction that wrote an outbox row waited for Kafka:
  - 197 s of row-lock waits in one S1 run;
  - with Kafka paused, business inserts failed with "Lock wait timeout exceeded".

  The relay now runs at READ COMMITTED. Row-lock time per S1 run fell from 224 s to 0.5 s.
  `OutboxRelayLockIT` pins this in both booking-service and payment-service.
- **GC.** In 512 MB containers the JVM chose SerialGC on its own, which caused stop-the-world pauses of up to 2 s
  during the spike. All images now run G1 (`-XX:MaxGCPauseMillis=100`). Gateway, booking and event get 768 MB.
- **Connection pools.** event-service and booking-service (per replica) default to 30 connections. MySQL allows
  151; the total in use is 110.

### Known gap: booking 5xx under the S1 spike

The spec's *target* of < 0.5 % booking 5xx (§16.3, "initial guesses, not promises") is **not met**: S1 measured
13 % (898 / 6,929 of all requests; 43 % of booking calls). Correctness is unaffected: no oversell, no lost money,
nothing stuck.

The cause was measured:
- about 3,000 seat-map reads in the burst (from MySQL by design, §15) saturate event-service on this laptop;
- some holds then exceed their 2 s timeout;
- the `eventHold` circuit breaker opens and fails further bookings fast, as designed (§14).

Not done (each changes the spec): a bulkhead that stops seat-map reads from starving holds, or a ~1 s seat-map
cache.

## AI assistant (plan 7)

`ai-assistant` (port 8086, not published; `/api/assistant/**` via the gateway) answers questions about events,
bookings, payments and policies. It uses **RAG** over `ai-assistant/src/main/resources/knowledge/*.md` and **four
read-only tools**, and Claude (`claude-opus-5-5`, effort `low`, 8,192 max tokens) writes the answers.

```
question -> embed (ONNX all-MiniLM-L6-v2, in-process) -> Qdrant top-4 (similarity >= 0.35)
         -> nothing relevant and no page context? fixed "couldn't find" answer, no model call, no cost
         -> prompt: static system prompt (cached) | history | <context> excerpts as data </context> + question
         -> Claude, up to 3 tool rounds: getEventDetails, listUpcomingEvents, getMyBookings, getBookingDetails
            (GET-only, allow-listed paths, the caller's own JWT relayed, results trimmed)
         -> answer + sources + suggested links (built from tool results only)
```

- **Read-only by construction.** No database, no Kafka, no internal secret, no signing key. The only outbound
  client permits `GET` to four path templates. "Book it for me" can't do anything, and the smoke test checks the
  database to prove it.
- **Endpoints:** `POST /api/assistant/chat`, `POST /api/assistant/chat/stream` (SSE: `delta` events, then `meta`,
  then `done`, or `error`), and `POST /api/assistant/admin/reindex` (ADMIN). Limits: message ≤ 1,000 characters,
  history ≤ 6 items, and 10 requests per minute per user at the gateway.
- **Degradation:**
  - Claude down → 503 `ASSISTANT_UNAVAILABLE`, with up to 3 relevant policy excerpts;
  - Qdrant down → 503, and health reports DOWN;
  - a refusal → a fixed polite message;
  - a `max_tokens` stop → the answer ends with "(truncated)";
  - a circuit breaker opens after repeated failures.
- **Setup:**
  - Put `ANTHROPIC_API_KEY=...` in `.env`. Without it the assistant still starts: off-topic questions are answered,
    and anything that needs the model returns 503 with the relevant excerpts.
  - The first start downloads the embedding model and the CPU PyTorch runtime (about 350 MB) into the
    `assistant_models` volume. `PYTORCH_FLAVOR=cpu` stops DJL from fetching multi-GB CUDA builds on GPU machines.
- **Data handling (A14):** the caller's own booking data (statuses, seats, amounts) can be sent to the Anthropic API
  to answer their question. Emails, passwords and payment-provider references never are.
- **Tests:**
  - `./mvnw -pl ai-assistant -am verify` runs 36 tests against a real Qdrant, with real embeddings and a fake
    Claude, so it never calls the API;
  - `bash infra/smoke/assistant-smoke.sh` (or `.ps1`) tests the assistant through the gateway;
  - `./mvnw -pl ai-assistant -am verify -Peval` with a real key runs the 25-question golden eval and writes
    `target/eval-report.json`. **It has not been run yet**, because there is no key in this environment.
- **Tuning note:** the spec's starting similarity threshold of 0.55 rejected correct matches with this embedding
  model. Measured scores: on-topic best hits 0.50–0.77, off-topic ≤ 0.11. The threshold is 0.35. Re-check it with the
  eval.

## Demo frontend (plan 8)

`frontend/` is a React 18 + Vite + TypeScript client of the public API through the gateway. Plan 8 made two small
backend changes: the gateway's CORS now also exposes `Retry-After` (so a 429 can disable Hold for that long), and
the assistant's stream `error` event carries the same `fallbackExcerpts` as its 503. The design follows spec §23.3 (the UI UX Pro Max "operations
console"): dark tokens, Fira Sans + Fira Code, and seat and status states shown as icon + text, never colour alone.

```bash
cd frontend
npm install
npm run dev              # http://localhost:5173  (gateway must be up on :8080)
npm test                 # Vitest: 85 tests
npm run build            # type-check + production build
npx playwright test      # smoke against the running stack (see e2e/smoke.spec.ts for the memory-friendly split)
```

| Route | What it shows |
|---|---|
| `/login`, `/register` | Visible labels, autocomplete, errors on blur |
| `/events` | Sale status as icon + text, and a countdown to the sale opening |
| `/events/:id` | **Live seat map** polled every 2 s (paused in background tabs). It's a keyboard grid: arrow keys move, Space selects, and each seat is named "Row A seat 1, available". Up to 4 seats; one Idempotency-Key per hold attempt |
| `/bookings/:id` | Hold countdown (announced at 5 min, 1 min and expiry only), pay, then poll to CONFIRMED. A retry after a decline uses a new key |
| `/me/bookings` | Status badges |
| any page | **Assistant panel**: the streamed answer, source chips, and suggested links (internal only, navigate-only). Model text is always rendered as text |

**Token storage:** the JWT is kept in memory plus `sessionStorage` (demo scope). An httpOnly cookie would resist
XSS better but needs backend changes.

## Live flash-sale demo (plan 9)

`http://localhost:5173/demo/flash-sale` (dev server only) proves the core guarantee on the running stack:

- **Two buyers, one seat:** two real accounts, each with its own JWT, race for the same seat through the gateway.
  Exactly one wins; the other gets `409 SEATS_UNAVAILABLE` and cannot pay.
- **Why, from the stores:** a read-only observer shows the Redis lock key's owner and TTL, the `event_db.seats` row,
  the booking and payment rows, outbox lag and Kafka consumer lag. Each stage is marked only when that evidence exists.
- **Payment scenarios:** switch the fake provider to decline, time out after charging (settled by the reconciler) or
  be down, then pay and watch the real states.
- **Load test:** N separate buyer accounts × S seats, fired at once server-side, then verified in SQL (no seat in two
  active bookings, plus the §16.4 invariants).

It is served by a dev-only Vite plugin (`frontend/demo-harness/`) that keeps `.env` secrets server-side. The fake
payment provider gained an internal-only `POST /__control/behaviour`. Data is polled, not pushed. See
[docs/demo-walkthrough.md](docs/demo-walkthrough.md) for what to click and say.

Observed on this laptop: under a 100-request burst, some Redis lock calls exceed booking-service's 200 ms timeout. The
filter then fails open (`booking.seatlock.bypassed`) and MySQL's conditional UPDATE refuses those requests instead.
There were still zero double bookings.

## Version decision

Verified on 2026-11-01 with a compatibility probe (plan 1, Task 1). It checked Resilience4j auto-config,
springdoc `/v3/api-docs`, the Spring Cloud context and a Testcontainers 2 MySQL container: 4/4 passed,
resolving `spring-boot` 4.1.2 and `spring-cloud-commons` 5.0.4. No fallback to Boot 3.5 was needed.

| Component | Version |
|---|---|
| Java | 21 |
| Spring Boot | 4.1.2 |
| Spring Cloud | 2025.1.4 (Gateway WebFlux 5.0.4, Eureka 5.0.3) |
| Resilience4j | `resilience4j-spring-boot4` 2.4.0 (pinned explicitly; not in `resilience4j-bom`) |
| springdoc | `springdoc-openapi-starter-webmvc-ui` 3.2.0 |
| Testcontainers | 2.0.5 (Boot-managed) |
| Flyway | 12.4.0 + `flyway-mysql` (Boot-managed) |
| MySQL driver | `mysql-connector-j` 9.7.0 (Boot-managed) |
| Kafka image | `apache/kafka:4.4.0` (KRaft, single node) |
| MockWebServer | `mockwebserver3` 5.5.1 |

## Customer catalog (redesign)

The Discover page lists only events with `listing = CATALOG`. Test, load-test and live-demo race events are
`INTERNAL` by default: still bookable by id, but never shown to customers. Flyway `V2__event_listing_metadata.sql` is
additive only (new nullable columns plus a `listing` flag defaulting to `INTERNAL`). Seed the fictional demo catalog
with `cd frontend && npm run seed:catalog`. It goes through the admin API, is idempotent (skips names that already
exist) and never modifies existing events, seats or bookings. All listings are fictional: invented dates and venues,
generated artwork, no artist photos or logos. E2E: `npx playwright test e2e/discover.spec.ts --workers=1` (screenshots go
to `docs/screenshots/`).
