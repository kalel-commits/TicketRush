# TicketRush Plan 2 — Event Service Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** event-service owns events and seat inventory: admins create events (seats generated), anyone reads the catalog and seat map (cached), booking-service holds seats through an idempotent, race-free internal endpoint, and Kafka `booking.confirmed` / `booking.cancelled` finalise or release seats exactly once.

**Architecture:** Spring Boot MVC service on port 8082, schema `event_db` (user `event_svc`). Events use JPA; seats use `JdbcClient` so the hold is one explicit conditional `UPDATE` (spec §9.2). Two security chains: `/internal/**` accepts only service tokens; `/api/**` accepts user tokens. Redis caches the catalog; Kafka consumers dedupe through `processed_events`.

**Tech Stack:** plan 1's stack, plus `spring-boot-starter-jdbc`, `spring-boot-starter-cache` + `spring-boot-starter-data-redis`, `spring-boot-starter-kafka` (Spring Kafka 4.1.2, Boot-managed), and tests with `testcontainers-kafka` (`org.testcontainers.kafka.KafkaContainer`, image `apache/kafka:4.4.0`).

**Spec:** `docs/superpowers/specs/2026-11-01-ticketrush-design.md` §6.2, §7.2, §8.1 step 5, §8.3–8.4 (event side), §9.2, §11.3–11.5, §13, §15 · **Roadmap:** plan 2

## Global Constraints

- Everything in plan 1's Global Constraints (versions, UTC `DATETIME(3)`, Flyway + `ddl-auto=validate`, `ProblemDetail` with `code`, virtual threads, Hikari 10, PowerShell twins for scripts).
- Seat DDL exactly as spec §6.2, including the `CHECK` on seat state and the indexes `uk_seat`, `idx_seat_event_status`, `idx_seat_booking`, `idx_seat_status_held`.
- `/internal/**` accepts only `ServiceTokens.decoder(INTERNAL_JWT_SECRET)` tokens with `ROLE_SERVICE`. `/api/**` accepts only `UserTokens.decoder(publicKey)` tokens. Neither chain accepts the other kind.
- Hold: 1–4 distinct seat ids, all-or-nothing, one transaction, ids sorted, one retry on MySQL deadlock, idempotent per `bookingId`. `holdSeconds` is limited to 30–900.
- Catalog cache: Redis, key prefix `ev:cache:`; event details and list TTL 10 min (`@Cacheable(sync = true)`); available-seat count TTL 5 s, evicted on confirm/release; cache errors fall back to the DB.
- Kafka consumer group `event-service`; record ack; `processed_events` insert in the same transaction as the effect; 3 retries (1 s, 2 s, 4 s) then `<topic>.DLT`; deserialisation errors go straight to the DLT.
- No commits unless the user authorizes them.

## Review Focus

1. **Overlapping multi-seat holds under contention** — two bookings that both include seat X must never both hold it, and neither may keep a partial hold. Test in Task 3 (`ConcurrencyIT.overlappingPairsAreAllOrNothing`).
2. **Retried hold after a lost response** — the same `bookingId` replayed must return the original hold (same `heldUntil`), not a 409. Test in Task 3.
3. **Hold on a seat id from a different event** — must be a 409, never a hold on the other event. Test in Task 3.
4. **Late `booking.cancelled` after the seat was re-held by someone else** — must not release the new holder's seat. Test in Task 4.
5. **Redis down** — catalog reads still work, straight from MySQL. Test in Task 2.

---

## File Structure

```
event-service/
  pom.xml, Dockerfile
  src/main/java/com/ticketrush/event/
    EventServiceApplication.java
    config/SecurityConfig.java        two chains: /internal/** (service tokens), /api/** (user tokens)
    config/CacheConfig.java           Redis cache TTLs, ev:cache: prefix, logging CacheErrorHandler
    config/KafkaConfig.java           DefaultErrorHandler + DeadLetterPublishingRecoverer
    catalog/Event.java, EventRepository.java
    catalog/EventDtos.java            CreateEventRequest, EventView, EventPage, SeatView
    catalog/EventAdminService.java    create event + generate seats (batch insert)
    catalog/CatalogService.java       cached reads
    catalog/AvailabilityService.java  cached available-seat count + eviction
    catalog/EventController.java      /api/events...
    hold/HoldRequest.java, HoldResponse.java
    hold/HoldService.java             conditional UPDATE, idempotency, deadlock retry
    hold/InternalHoldController.java  POST /internal/events/{eventId}/holds
    messaging/BookingEventsListener.java  booking.confirmed / booking.cancelled
    messaging/SeatFinalizer.java      transactional dedupe + confirm/release
  src/main/resources/application.yml
  src/main/resources/db/migration/V1__events_and_seats.sql
  src/test/java/com/ticketrush/event/
    IntegrationTestBase.java          MySQL + Redis + Kafka containers, keys, token helpers
    EventAdminIT, EventQueryIT, HoldIT, ConcurrencyIT, BookingEventsIT, InternalSecurityIT
```
Modify: root `pom.xml` (module), `docker-compose.yml` (service).

---

### Task 1: Module, schema, security chains, event creation

**Interfaces (produced):**
- `POST /api/events` (ADMIN) `{name, venue, startsAt, saleOpensAt, saleClosesAt?, priceCents, rows (1–26), seatsPerRow (1–50)}` → `201 EventView`. `saleClosesAt` defaults to `startsAt`. Rows are lettered `A…`; `seatNo` = row letter + 1-based number (`A1`…`Z50`). `total_seats = rows × seatsPerRow`.
- `record EventView(long id, String name, String venue, Instant startsAt, Instant saleOpensAt, Instant saleClosesAt, long priceCents, int totalSeats, int availableSeats)`.
- Validation → 400 `VALIDATION_FAILED`: blank name/venue, `priceCents ≤ 0`, rows/seatsPerRow out of range, `saleOpensAt ≥ saleClosesAt`, `startsAt` null.

- [ ] **Step 1: Failing tests** `EventAdminIT`: ADMIN creates a 3×4 event → 201, `totalSeats=12`, DB has 12 seats `A1…C4`, all `AVAILABLE`; USER → 403; anonymous → 401; service token → 401; each invalid field → 400 with `errors.<field>` (cross-field rule reported on `saleOpensAt`). `InternalSecurityIT`: `POST /internal/events/1/holds` with no token → 401, with a user ADMIN token → 401, with a service token → not 401/403; `GET /internal/anything` with a service token → 404 (chain allows, nothing mapped); `/foo` → 401 (anonymous) / 403 (USER).
- [ ] **Step 2: Run** `./mvnw -pl event-service -am verify` — Expected: FAIL (compilation).
- [ ] **Step 3: Implement** the module, `V1__events_and_seats.sql` (spec §6.2 events, seats, processed_events), the `Event` entity, `EventAdminService.create` (save event, then `JdbcClient` batch insert of seats in one transaction), `SecurityConfig` (two chains, decoders built inline; the INTERNAL secret from `ticketrush.internal-jwt-secret`, the public key from `ticketrush.jwt.public-key`).
- [ ] **Step 4: Run** — Expected: PASS.

### Task 2: Catalog reads and caching

**Interfaces:** `GET /api/events?page=0&size=20` → `EventPage(List<EventView> content, int page, int size, long totalElements)` ordered by `startsAt`, size capped at 50. `GET /api/events/{id}` → `EventView` or 404 `NOT_FOUND`. `GET /api/events/{id}/seats` → `List<SeatView(long id, String seatNo, String status)>` ordered by id, read straight from DB (correctness over speed). `AvailabilityService.availableSeats(eventId)` cached 5 s; `evict(eventId)`.

- [ ] **Step 1: Failing tests** `EventQueryIT`:
  - list pagination and order; details incl. `availableSeats`; seat map; unknown id → 404 `NOT_FOUND`.
  - **Caching proof:** GET details, change `events.name` via JDBC, GET again → still the old name; after `cacheManager.getCache("ev-event").clear()` → new name.
  - **Redis down (Review Focus 5):** pause the Redis container (`docker pause` through Testcontainers), then GET details → 200 from DB; unpause.
- [ ] **Step 2: Run** — FAIL.
- [ ] **Step 3: Implement** `CatalogService` (`@Cacheable(cacheNames="ev-event", key="#id", sync=true)`, `ev-events` list keyed by page+size), `AvailabilityService` (`ev-avail`, TTL 5 s), `CacheConfig` (per-cache TTLs, `ev:cache:` prefix, JDK serialisation of `Serializable` records, short Redis command timeout 200 ms, `CacheErrorHandler` that logs and lets the method run), and creation writing the new event into `ev-event` (cache warm before the sale).
- [ ] **Step 4: Run** — PASS.

### Task 3: Internal idempotent seat hold

**Interfaces:**
- `POST /internal/events/{eventId}/holds` (service token) `{bookingId: UUID, seatIds: [long] (1–4, distinct), holdSeconds (30–900)}` → `200 HoldResponse(Instant heldUntil, long amountCents, List<String> seatNos)`.
- 409 `SALE_NOT_OPEN` when now ∉ [saleOpensAt, saleClosesAt); 409 `SEATS_UNAVAILABLE` when any seat isn't available to this booking (also for ids from another event); 404 `NOT_FOUND` for an unknown event; 400 for bad input.
- `HoldService.hold(long eventId, HoldRequest req): HoldResponse` — one transaction:
  ```
  UPDATE seats SET status='HELD', booking_id=:b, held_until=:until, updated_at=NOW(3)
   WHERE event_id=:e AND id IN (:sortedIds) AND status='AVAILABLE'
  -- then, in the same transaction:
  SELECT COUNT(*), MIN(held_until) FROM seats WHERE event_id=:e AND id IN (:ids) AND booking_id=:b AND status='HELD'
  ```
  count == n → success (an idempotent replay returns the stored `held_until`); otherwise throw → rollback → 409. `DeadlockLoserDataAccessException` / `CannotAcquireLockException` → retry the whole transaction once. Evict `ev-avail` for the event after commit.

- [ ] **Step 1: Failing tests**
  - `HoldIT`:
    - Success → 200, seats HELD with `booking_id`, `amountCents = price × n`, seat numbers returned.
    - Replay with the same `bookingId` → 200 with the identical `heldUntil` (Review Focus 2).
    - Another booking on an overlapping set {s1, s2} where s2 is held → 409 and s1 still AVAILABLE.
    - Seat id from another event → 409 and that seat untouched (Review Focus 3).
    - Before the sale opens / after it closes → 409 `SALE_NOT_OPEN`.
    - 5 seats, duplicate ids, `holdSeconds` 10 → 400; unknown event → 404.
  - `ConcurrencyIT`:
    - `singleSeatHasExactlyOneWinner`: 500 virtual threads, distinct bookingIds, one seat, calling `HoldService` → exactly 1 success, 499 `SEATS_UNAVAILABLE`; the seat's `booking_id` = the winner.
    - `overlappingPairsAreAllOrNothing`: 200 threads each holding 2 random seats out of 10 → for every booking the number of held seats ∈ {0, 2}; no seat counted twice (Review Focus 1).
- [ ] **Step 2: Run** — FAIL.
- [ ] **Step 3: Implement** `HoldService` (`TransactionTemplate` so the retry wraps the whole transaction), `InternalHoldController`, and DTO validation.
- [ ] **Step 4: Run** — PASS.

### Task 4: Kafka consumers — confirm and release exactly once

**Interfaces:**
- Topics `booking.confirmed` and `booking.cancelled`. The value is JSON `EventEnvelope` with payload `{bookingId, eventId, seatIds}` (confirmed) or `{bookingId, eventId, reason}` (cancelled); the key is `bookingId`.
- `SeatFinalizer.confirm(UUID messageId, UUID bookingId, long eventId, int expectedSeats): boolean` / `release(UUID messageId, UUID bookingId, long eventId): boolean` (returns false when the message was a duplicate) — `@Transactional`: insert `processed_events` (duplicate → return without effect) then:
  - confirm: `UPDATE seats SET status='BOOKED', held_until=NULL WHERE booking_id=? AND status='HELD'`. If rows < expected and the seats aren't already BOOKED for this booking → log `ERROR invariant violation` and increment the Micrometer counter `event.invariant.violations` (spec §9.5).
  - release: `UPDATE seats SET status='AVAILABLE', booking_id=NULL, held_until=NULL WHERE booking_id=? AND status='HELD'`.
  - both evict `ev-avail`.
- `KafkaConfig`: `DefaultErrorHandler(DeadLetterPublishingRecoverer, ExponentialBackOff 1 s ×2, max 3 retries)`; a JSON parse error is classified as not retryable.

- [ ] **Step 1: Failing tests** `BookingEventsIT` (Kafka container; the test produces with `KafkaTemplate<String,String>`):
  - `booking.confirmed` → seats BOOKED within 10 s.
  - The same envelope twice → one `processed_events` row, seats BOOKED, no error.
  - `booking.cancelled` → seats AVAILABLE with `booking_id` NULL.
  - `booking.cancelled` for booking X after the seat was re-held by Y → the seat stays HELD by Y (Review Focus 4).
  - Malformed JSON → arrives on `booking.confirmed.DLT`.
  - Confirm when the seats were never held → invariant counter = 1, nothing changed.
- [ ] **Step 2: Run** — FAIL.
- [ ] **Step 3: Implement** the listener, `SeatFinalizer`, and `KafkaConfig`.
- [ ] **Step 4: Run** — PASS.

### Task 5: Compose wiring and smoke

- [ ] **Step 1:** Dockerfile (plan 1 pattern) and a Compose service `event-service` (no published port; mounts `infra/keys/public` only; env `DB_HOST`, `EVENT_DB_PASSWORD`, `REDIS_HOST`, `KAFKA_BOOTSTRAP=kafka:9092`, `INTERNAL_JWT_SECRET`, `EUREKA_URL`; `depends_on` mysql, redis, kafka, discovery-server healthy).
- [ ] **Step 2:** Extend `infra/smoke/foundation-smoke.(sh|ps1)` → rename to `infra/smoke/smoke.(sh|ps1)` with event checks:
  - admin login;
  - `POST /api/events` (2×3) through the gateway → 201;
  - `GET /api/events/{id}` → `availableSeats=6`;
  - `GET /api/events/{id}/seats` → 6 seats;
  - `POST /api/events` as USER → 403.
- [ ] **Step 3: Run** — cold `docker compose down -v && up -d --build`, then both smoke scripts → `SMOKE PASSED`. Full `./mvnw verify` (stack stopped) → all green.
