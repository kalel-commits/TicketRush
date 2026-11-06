# TicketRush Plan 3 — Booking Service Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** booking-service turns "user wants seats" into a booking safely. It is idempotent per request, enforces a hard 4-seat limit per user per event, filters the crowd in Redis, holds seats through event-service, runs the booking state machine, expires unpaid bookings, publishes outcomes reliably through a transactional outbox, and confirms bookings when payments succeed.

**Architecture:** Spring Boot MVC on port 8083, schema `booking_db` (user `booking_svc`), run as **2 instances**. Plain `JdbcTemplate` SQL (no JPA), so every state transition is a visible conditional `UPDATE`. Redis provides an atomic all-or-nothing Lua seat filter (Approach B), with MySQL in event-service as the source of truth. event-service is called over `RestClient` with a short-lived service token. Outcomes go through the outbox to Kafka; `payment.succeeded` comes in from Kafka.

**Tech Stack:** plan 1–2 stack (`spring-boot-starter-jdbc`, `-data-redis`, `-kafka`, `-security-oauth2-resource-server`, Eureka client + Spring Cloud LoadBalancer), plus `mockwebserver3` and `testcontainers-kafka` for tests.

**Spec:** §6.3, §7.1, §8.1, §8.3, §8.4, §9.1, §9.3, §11.1–11.4, §12, §13 · **Roadmap:** plan 3 ("Done when": concurrency proofs 1–4 of §16.1 pass)

## Global Constraints

- Plan 1–2 constraints apply. Test containers use `connectionTimeZone=UTC`, the same as production.
- Booking ids are `UuidV7.next()`, stored as `BINARY(16)`. Booking statuses are exactly `HOLDING, PENDING_PAYMENT, CONFIRMED, CANCELLED, EXPIRED, FAILED`.
- Every transition is `UPDATE … WHERE id=? AND status=<from> [guard]`. Side effects (quota release, outbox row) happen only when exactly 1 row changed, and in the same transaction.
- **No DB transaction spans the Redis call, the event-service call or a Kafka send.**
- Redis lock key `bk:seatlock:{eventId}:{seatId}`, owner token `userId:idempotencyKey`. Acquire with one Lua script: all or none, re-entrant for the same owner, TTL = hold + 60 s. Release by compare-and-delete. Any Redis error skips the filter (falls back to Approach A).
- Quota: ensure the row exists (`INSERT … ON DUPLICATE KEY UPDATE user_id = user_id`), then `UPDATE … SET seats_reserved = seats_reserved + :n WHERE … AND seats_reserved + :n <= :max`. Max 4 (property `ticketrush.booking.max-seats-per-event`).
- Hold duration 600 s (`ticketrush.booking.hold-seconds`); HOLDING timeout 60 s (`ticketrush.booking.holding-timeout-seconds`).
- event-service client: connect 500 ms, read 2 s, service token from `ServiceTokens.provider(INTERNAL_JWT_SECRET, "booking-service", clock)`. Outcome mapping:
  - 200 → PENDING_PAYMENT, 201.
  - 409 → FAILED, return 409 with the same `code`.
  - 404 → FAILED, 404.
  - Timeout, 5xx or connection error → FAILED **and** outbox `booking.cancelled` (the hold may have committed), 503 `DEPENDENCY_UNAVAILABLE`.
  - (Circuit breaker and retries come in plan 5.)
- Outbox relay every 500 ms, `SELECT … FOR UPDATE SKIP LOCKED LIMIT 100`, synchronous send, then mark published. Producer `acks=all`, idempotent.
- Topics declared here (`NewTopic`): `booking.confirmed`, `booking.cancelled`, `payment.refund.requested` (3 partitions, key = bookingId) and `payment.succeeded.DLT` (1 partition). The consumer sets `allow.auto.create.topics=false`.
- Sweeper every 15 s (property): `SELECT id … WHERE status IN ('HOLDING','PENDING_PAYMENT') AND expires_at <= NOW(3) LIMIT 100 FOR UPDATE SKIP LOCKED`.
- No commits unless the user authorizes them.

## Review Focus

1. **The same Idempotency-Key fired concurrently** — exactly one booking. Every response references it, and the twin never deletes the winner's Redis locks. Test in Task 2 (proof 4).
2. **One user firing many parallel requests with different keys** — never more than 4 seats reserved, and the quota row equals the seats of active bookings. Test in Task 2 (proof 3).
3. **event-service times out after committing the hold** — the booking is FAILED, the quota is released, a `booking.cancelled` outbox row exists, and the caller gets 503. Test in Task 1.
4. **Sweeper vs payment racing on an expiring booking** — exactly one of CONFIRMED or EXPIRED+refund. Test in Task 4.
5. **Two booking instances sweeping and relaying at once** — no booking expired twice, no quota released twice, no outbox row lost. Test in Task 3.

---

## File Structure

```
booking-service/
  pom.xml, Dockerfile
  src/main/resources/application.yml, db/migration/V1__bookings.sql, redis/acquire.lua, redis/release.lua
  src/main/java/com/ticketrush/booking/
    BookingServiceApplication.java
    config/SecurityConfig.java          /internal/** service tokens; /api/** user tokens
    config/ClientConfig.java            @LoadBalanced RestClient for event-service (+ service token, timeouts)
    config/KafkaConfig.java             NewTopic beans, DefaultErrorHandler + DLT
    domain/BookingStatus.java
    domain/Booking.java                 record (row view)
    domain/BookingRepository.java       SQL: insert, find, conditional transitions, quota, idempotency lookup
    domain/Uuids.java                   UUID <-> BINARY(16)
    seatlock/SeatLocks.java             Redis Lua acquire / compare-delete, fail-open
    eventclient/EventClient.java        hold(...) -> HoldOutcome (sealed: Held | Rejected | Ambiguous)
    outbox/OutboxWriter.java            append(aggregateId, topic, payload) inside the caller's tx
    outbox/OutboxRelay.java             @Scheduled publisher (SKIP LOCKED)
    api/BookingDtos.java                CreateBookingRequest, BookingView, BookingPage
    api/BookingService.java             create / cancel orchestration (spec §8.1)
    api/BookingController.java          /api/bookings...
    api/InternalBookingController.java  /internal/bookings/{id}, /internal/bookings/status
    jobs/BookingSweeper.java            expiry (spec §12)
    messaging/PaymentSucceededListener.java + PaymentSucceededHandler.java (spec §8.3)
  src/test/java/com/ticketrush/booking/
    IntegrationTestBase.java            MySQL + Redis + Kafka, fake event-service (MockWebServer), tokens
    FakeEventService.java               thread-safe seat ownership + failure modes
    BookingCreateIT, ConcurrencyProofIT, OutboxAndSweeperIT, CancelIT, PaymentSucceededIT, InternalApiIT
```

---

### Task 1: Module, schema, security, create + read (Approach A)

**Interfaces:**
- `POST /api/bookings` (user token). Header `Idempotency-Key` required (1–64 chars) → 400 if missing. Body `{eventId: long, seatIds: [long] 1–4 distinct}`. Responses:
  - `201 BookingView` on a new booking.
  - `200 BookingView` on a replay with the same body hash.
  - 422 `IDEMPOTENCY_KEY_REUSED` when the hash differs.
  - 409 `SEAT_LIMIT_EXCEEDED`, 409 `SEATS_UNAVAILABLE` / `SALE_NOT_OPEN` (passed through from event-service), 404 `NOT_FOUND` for an unknown event, 503 `DEPENDENCY_UNAVAILABLE`.
- `record BookingView(UUID id, long eventId, String status, List<Long> seatIds, List<String> seatNos, Long amountCents, Instant expiresAt, String failureReason, Instant createdAt)`.
- `GET /api/bookings/{id}` — owner only, otherwise 404 (no existence leak). `GET /api/bookings/me?page&size` — newest first, size ≤ 50, `BookingPage(content, page, size)`.
- `request_hash` = SHA-256 hex of `eventId + ":" + sorted seatIds`.
- Schema = spec §6.3 (`bookings` with `payment_id`, `booking_seats`, `user_event_quota`, `outbox_events`, `processed_events`).
- `EventClient.hold(long eventId, UUID bookingId, List<Long> seatIds, int holdSeconds): HoldOutcome`, where `sealed interface HoldOutcome permits Held(Instant heldUntil, long amountCents, List<String> seatNos), Rejected(int status, String code), Ambiguous(String reason)`.

- [x] **Step 1: Failing tests** `BookingCreateIT`:
  - happy path → 201 PENDING_PAYMENT, amount and seatNos from the fake event-service, `expires_at` = heldUntil, quota 1;
  - replay → 200 with the same id;
  - same key with different seats → 422;
  - 5th seat for the same event → 409 `SEAT_LIMIT_EXCEEDED`;
  - fake answers 409 → 409 `SEATS_UNAVAILABLE`, booking FAILED, quota back to 0, **no** outbox row;
  - fake answers 409 `SALE_NOT_OPEN` → passed through;
  - fake times out → 503, FAILED, quota 0, one `booking.cancelled` outbox row (Review Focus 3);
  - missing Idempotency-Key → 400;
  - another user's `GET /api/bookings/{id}` → 404; `/me` lists only own bookings;
  - no token → 401; service token on `/api` → 401.
- [x] **Step 2:** Run → FAIL. **Step 3:** Implement. **Step 4:** Run → PASS.

### Task 2: Redis seat filter + concurrency proofs 1–4

**Interfaces:** `SeatLocks.acquire(long eventId, List<Long> seatIds, String owner, Duration ttl): boolean` (Redis error → returns true and increments the counter `booking.seatlock.bypassed`); `SeatLocks.release(long eventId, List<Long> seatIds, String owner)` (best effort). `BookingService.create` order (spec §8.1):
1. Idempotency lookup.
2. `acquire` — false → 409 `SEATS_UNAVAILABLE`, no DB write.
3. tx#1: quota + insert HOLDING. A duplicate key on the idempotency index returns the twin's booking and **does not** release the locks.
4. Hold call.
5. tx#2 outcome. On any failure path, compare-delete the locks.

- [x] **Step 1: Failing tests** `ConcurrencyProofIT` (MockMvc, virtual threads, distinct user tokens):
  1. 1 seat, 500 users → exactly 1 booking in HOLDING/PENDING_PAYMENT, 499 × 409, **and the fake event-service received exactly 1 hold call** (the Redis filter absorbed the rest).
  2. Same with Redis paused → still exactly 1 active booking (correctness from the fake's atomic ownership, standing in for MySQL).
  3. One user, 20 parallel requests, different keys, 1 different seat each → ≤ 4 non-FAILED bookings; quota `seats_reserved` = sum of `seat_count` of active bookings.
  4. Same key ×20 in parallel → exactly 1 bookings row; every 200/201 response has that id; the seat's Redis key still holds the owner token.
- [x] **Step 2–4:** RED → implement (Lua scripts in `resources/redis`) → GREEN.

### Task 3: Outbox relay, cancel, sweeper

**Interfaces:**
- `OutboxWriter.append(UUID aggregateId, String topic, Object payload)` (`MANDATORY` propagation) writes an `EventEnvelope` JSON with eventId = outbox id.
- `OutboxRelay.publishBatch(): int`.
- `POST /api/bookings/{id}/cancel` (owner): PENDING_PAYMENT → CANCELLED, release quota, outbox `booking.cancelled {bookingId, eventId, reason:"CANCELLED"}`; any other state → 409 `INVALID_STATE` (new `ErrorCode`, 409).
- `BookingSweeper.sweep(): int`: HOLDING → FAILED (`HOLDING_TIMEOUT`), PENDING_PAYMENT → EXPIRED, each with quota release + outbox `booking.cancelled`; after commit, compare-delete the Redis locks.

- [x] **Step 1: Failing tests** `OutboxAndSweeperIT` + `CancelIT`:
  - an outbox row → appears on Kafka `booking.cancelled` with key = bookingId and the envelope eventId = outbox id; row marked published;
  - two relays in parallel → each row sent, none lost (count published rows = rows);
  - sweeper expires an overdue PENDING_PAYMENT booking: EXPIRED, quota released, outbox row; a booking whose `expires_at` is in the future is untouched;
  - two `sweep()` calls in parallel on 50 overdue bookings → each expired once, quota never negative, exactly 50 outbox rows (Review Focus 5);
  - cancel → CANCELLED + outbox; cancel twice → 409 `INVALID_STATE`; cancel someone else's booking → 404.
- [x] **Step 2–4:** RED → implement → GREEN.

### Task 4: payment.succeeded consumer + internal API

**Interfaces:**
- Topic `payment.succeeded`, payload `{paymentId, bookingId, amountCents}`.
- `PaymentSucceededHandler.handle(UUID messageId, UUID paymentId, UUID bookingId)`, one tx:
  - dedupe in `processed_events`;
  - `UPDATE … SET status='CONFIRMED', payment_id=? WHERE id=? AND status='PENDING_PAYMENT' AND expires_at > NOW(3)`;
  - 1 row → outbox `booking.confirmed {bookingId, eventId, seatIds}`;
  - 0 rows and already CONFIRMED with this payment_id → no-op;
  - otherwise → outbox `payment.refund.requested {paymentId, bookingId, reason}`.
- `GET /internal/bookings/{id}` (service token) → `{id, userId, eventId, status, amountCents, expiresAt, paymentId}` or 404.
- `POST /internal/bookings/status {bookingIds: [UUID] ≤ 200}` → `[{bookingId, status, paymentId}]`, with unknown ids omitted.

- [x] **Step 1: Failing tests** `PaymentSucceededIT` + `InternalApiIT`:
  - payment for a PENDING booking → CONFIRMED, payment_id set, `booking.confirmed` outbox row;
  - duplicate message → one outbox row;
  - payment after expiry → refund requested;
  - payment for a CANCELLED booking → refund requested;
  - **race (Review Focus 4):** an overdue booking with `sweep()` and `handle()` run concurrently 20 times → each booking ends either CONFIRMED (no refund row) or EXPIRED with exactly one refund row, never both;
  - internal endpoints accept only service tokens; status batch returns the known ids.
- [x] **Step 2–4:** RED → implement → GREEN.

### Task 5: Compose (2 instances) + end-to-end smoke

- [x] Dockerfile; Compose service `booking-service` with `deploy.replicas: 2` (no published ports, public key folder only, `INTERNAL_JWT_SECRET`, depends on mysql/redis/kafka/discovery/event-service healthy).
- [x] Extend `infra/smoke/smoke.(sh|ps1)`:
  1. A user books seat 1 of the smoke event → 201 PENDING_PAYMENT, and the seat map shows HELD.
  2. Replay with the same key → 200 with the same id.
  3. A second user books the same seat → 409.
  4. Cancel → CANCELLED, and within 20 s the seat map shows AVAILABLE again (outbox → Kafka → event-service).
  5. Eureka lists 2 BOOKING-SERVICE instances.
- [x] Cold `down -v` / `up -d --build` → both smoke scripts pass; full `./mvnw verify` (stack stopped) green.
