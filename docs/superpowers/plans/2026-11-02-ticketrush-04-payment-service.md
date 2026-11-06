# TicketRush Plan 4 — Payment Service + Fake Provider Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** a user can pay for a PENDING_PAYMENT booking exactly once. The payment attempt is written as PROCESSING *before* any money moves, so a second concurrent attempt is stopped by a unique index. Ambiguous provider outcomes stay PROCESSING instead of guessing. Success flows to booking-service via the outbox. Refund requests from booking-service are executed idempotently against the provider.

**Architecture:** two new modules.
- `payment-service` runs on port 8085, schema `payment_db` (user `payment_svc`), 1 instance, Eureka-registered. Plain `JdbcTemplate` SQL with conditional `UPDATE`s, the same as booking-service. It calls booking-service over a `@LoadBalanced` `RestClient` with a service token. It calls the provider over a plain `RestClient`, because the provider is external and not in Eureka. Outbox → Kafka `payment.succeeded`; it consumes `payment.refund.requested`.
- `fake-payment-provider` runs on port 8090. It is a tiny external PSP stub with in-memory state, is not registered in Eureka, and has no DB.

**Tech Stack:** booking-service's stack minus Redis (`spring-boot-starter-webmvc`, `-jdbc`, `-flyway` + `flyway-mysql`, `-kafka`, `-security-oauth2-resource-server`, `-actuator`, `-validation`, Eureka client + Spring Cloud LoadBalancer), with `mockwebserver3` + Testcontainers MySQL/Kafka for tests. The provider uses only `-webmvc`, `-actuator` and `-validation`.

**Spec:** `docs/superpowers/specs/2026-11-01-ticketrush-design.md`: §6.4, §6.5, §7.3, §8.2, §8.4 (refund), §10 (provider behaviour; the reconciler is plan 5), §11, §13, §14 (timeouts only). **Roadmap:** plan 4. "Done when": concurrency proof 6 and the ambiguous-timeout test pass, and the happy path works end to end via the gateway.

## Global Constraints

- Plan 1–3 constraints apply. Base package `com.ticketrush.payment`; provider package `com.ticketrush.provider`.
- Payment ids are `UuidV7.next()`, stored as `BINARY(16)`. The **payment id is also the provider `Idempotency-Key`** for the charge. The refund key is `refund-<paymentId>`.
- Payment statuses are exactly `PROCESSING, SUCCEEDED, FAILED, REFUND_PENDING, REFUNDED`. Every transition is `UPDATE … WHERE id=? AND status=<from>`. Side effects (outbox row) happen only when exactly 1 row changed, in the same transaction.
- Schema = spec §6.4 verbatim, including the generated column `active_booking_id … STORED`, `UNIQUE uk_payment_active_booking`, `lookup_attempts` and `verified_at` (both unused until plan 5). The generated column is never written by application SQL.
- **No DB transaction spans the booking-service call, the provider call or a Kafka send.**
- booking-service client: connect 500 ms, read 1 s, base URL `http://booking-service` (`@LoadBalanced`). The service token comes from `ServiceTokens.provider(INTERNAL_JWT_SECRET, "payment-service", clock)`.
- Provider client: connect 500 ms, read 3 s, **no retry**, base URL property `ticketrush.provider.base-url` (env `PAYMENT_PROVIDER_URL`, default `http://localhost:8090`). Built from a **plain** builder.
- **Lesson from plan 3:** declare a plain `@Primary @Scope("prototype") RestClient.Builder` next to the `@LoadBalanced` one. Otherwise Eureka's own transport picks up the load-balanced builder and the service never registers. Copy `booking-service`'s `ClientConfig` pattern and its `ClientConfigTest`.
- Charge outcome mapping (spec §8.2 step 5):
  - 2xx with `status=SUCCEEDED` → SUCCEEDED + outbox `payment.succeeded`, 200.
  - 2xx with `status=DECLINED` → FAILED (`DECLINED`), 402 `DECLINED`.
  - Read timeout, 5xx, or the connection reset after the request was sent → stays PROCESSING, **202**.
  - Connect refused / connect timeout (request never sent) → FAILED (`PROVIDER_UNAVAILABLE`), 503 `DEPENDENCY_UNAVAILABLE`.
  - Circuit breaker, retries and the reconciler come in plan 5.
- Topics declared here (`NewTopic`): `payment.succeeded` (3 partitions, key = bookingId) and `payment.refund.requested.DLT` (1). Error handler and DLT are the same as booking-service: 1 s/2 s/4 s back-off, then DLT, and `IllegalArgumentException` is not retried.
- Outbox relay every 500 ms, the same SQL as booking-service (`FOR UPDATE SKIP LOCKED LIMIT 100`, synchronous send, then mark published). Producer `acks=all`, idempotent.
- The tests' "already past" timestamps must be backdated by ≥ 1 hour, not seconds (the Docker Desktop VM clock steps backwards).
- No commits unless the user authorizes them.

## Review Focus

1. **Two different Idempotency-Keys paying one booking concurrently.** Exactly one provider charge; every loser gets 409 `PAYMENT_IN_PROGRESS_OR_DONE` with the winner's id. Test in Task 2 (proof 6).
2. **The provider charges, then the response is lost (read timeout).** The attempt stays PROCESSING and returns 202. A replay with the same key returns 202 with no second charge. A new key gets 409 because the slot stays taken. No `payment.succeeded` row. Test in Task 2.
3. **The same Idempotency-Key fired concurrently (double-click).** One attempt, one charge, and every success response has the same id. Test in Task 2.
4. **The provider is unreachable before the request is sent vs. after.** Connect refused → FAILED, slot freed, the user can retry. 5xx → PROCESSING, because money may have moved. Test in Task 2.
5. **`payment.refund.requested` delivered twice or concurrently.** Exactly one provider refund call; the payment ends REFUNDED. Test in Task 3.

---

## File Structure

```
fake-payment-provider/
  pom.xml, Dockerfile
  src/main/resources/application.yml
  src/main/java/com/ticketrush/provider/
    FakeProviderApplication.java
    ProviderSettings.java        @ConfigurationProperties("provider"): declineRate, latencyMs, timeoutAfterChargeRate, timeoutSleepMs, down
    ChargeStore.java             ConcurrentHashMap by idempotency key; charges + refunds
    ProviderController.java      POST /charges, GET /charges?idempotencyKey=, GET /charges, POST /refunds
  src/test/java/com/ticketrush/provider/ProviderControllerTest.java

payment-service/
  pom.xml, Dockerfile
  src/main/resources/application.yml, db/migration/V1__payments.sql
  src/main/java/com/ticketrush/payment/
    PaymentServiceApplication.java       @EnableScheduling, Clock bean
    config/SecurityConfig.java           copy of booking's: /internal/** service tokens, /api/** user tokens
    config/ClientConfig.java             @Primary plain builder, @LoadBalanced builder, bookingRestClient, providerRestClient
    config/KafkaConfig.java              NewTopics + DefaultErrorHandler/DLT
    domain/PaymentStatus.java, Payment.java (record), PaymentRepository.java, Uuids.java
    bookingclient/BookingClient.java     get(UUID) -> BookingLookup (sealed)
    provider/ProviderClient.java         charge(...) -> ChargeOutcome, refund(...) -> RefundOutcome (sealed)
    outbox/OutboxWriter.java, OutboxRelay.java   (same as booking-service's)
    api/PaymentDtos.java                 CreatePaymentRequest, PaymentView
    api/PaymentResponses.java            of(Payment) -> ResponseEntity (status-code mapping below)
    api/PaymentService.java              pay orchestration (spec §8.2)
    api/PaymentController.java           /api/payments...
    messaging/RefundRequestedListener.java + RefundHandler.java (spec §8.4)
  src/test/java/com/ticketrush/payment/
    IntegrationTestBase.java             MySQL + Kafka, FakeBookingService + FakeProvider (MockWebServer), tokens
    FakeBookingService.java, FakeProvider.java
    config/ClientConfigTest.java
    PayIT.java, PaymentConcurrencyIT.java, OutboxIT.java, RefundIT.java
```

Also modify: root `pom.xml` (2 modules), `docker-compose.yml`, `infra/smoke/smoke.(sh|ps1)`, roadmap.

Outbox code is copied from booking-service, not shared. `common` has no JDBC dependency, and moving the code there would touch booking-service for no behaviour gain. This is noted as a plan 5 cleanup candidate.

---

### Task 1: fake-payment-provider

**Files:** the `fake-payment-provider/` tree above; root `pom.xml` `<module>fake-payment-provider</module>`.

**Interfaces (HTTP, JSON):**
- `POST /charges`
  - Header `Idempotency-Key` (required, otherwise 400).
  - Body `{amountCents: long > 0, reference: string}`.
  - Response 200 `{chargeId: "ch_<uuid>", idempotencyKey, amountCents, reference, status: "SUCCEEDED"|"DECLINED", refunded: false, createdAt}`.
  - A repeat with the same key returns the **stored** charge without re-rolling the decline or creating a new charge.
- `GET /charges?idempotencyKey=K` → 200 charge, or 404.
- `GET /charges` → 200 list of all charges, used by the plan 5 reconciliation report.
- `POST /refunds`
  - Header `Idempotency-Key` (required).
  - Body `{chargeId}`.
  - Response 200 `{refundId: "re_<uuid>", chargeId, status: "REFUNDED"}`; sets `refunded=true`.
  - Same key → same refund.
  - Unknown charge → 404. Charge not SUCCEEDED → 409.
- Behaviour, from env: `PROVIDER_DECLINE_RATE` (0.0), `PROVIDER_LATENCY_MS` (0), `PROVIDER_TIMEOUT_AFTER_CHARGE_RATE` (0.0), `PROVIDER_TIMEOUT_SLEEP_MS` (5000), `PROVIDER_DOWN` (false).
  - `down=true` → every endpoint returns 503.
  - Timeout-after-charge: the charge is **stored first**, then the request sleeps `timeoutSleepMs` before responding.
- `ChargeStore` methods are atomic per key (`computeIfAbsent`), so concurrent same-key charges create one charge.

- [x] **Step 1: Failing tests** `ProviderControllerTest` (`@SpringBootTest` + MockMvc, settings overridden per test through a `ProviderSettings` bean that the test mutates):
  - charge → 200 SUCCEEDED; the same key twice → the same `chargeId`, and `GET /charges` has 1 entry;
  - `declineRate=1.0` → DECLINED, and a repeat with the same key is still DECLINED (stored, not re-rolled);
  - lookup by key → 200; an unknown key → 404;
  - 20 parallel charges with one key → exactly 1 charge stored;
  - refund twice with the same key → the same `refundId`, `refunded=true`; a refund of a DECLINED charge → 409;
  - `down=true` → 503 on `POST /charges`;
  - missing `Idempotency-Key` → 400.
- [x] **Step 2:** Run `./mvnw -pl fake-payment-provider verify` → FAIL.
- [x] **Step 3:** Implement.
- [x] **Step 4:** Run → PASS.

### Task 2: payment-service — schema, pay flow, concurrency

**Files:** everything in `payment-service/` except `messaging/`, `outbox/OutboxRelay.java` and `KafkaConfig`'s DLT parts; root `pom.xml` module.

**Interfaces:**
- `POST /api/payments` (user token).
  - Header `Idempotency-Key` (1–64 chars), otherwise 400.
  - Body `{bookingId: UUID}`.
  - `request_hash` = SHA-256 hex of `bookingId.toString()`.
- `record PaymentView(UUID id, UUID bookingId, long amountCents, String status, String failureReason, Instant createdAt)`.
- **The response is a pure function of the stored attempt**, so a replay returns exactly what the first call returned. `PaymentResponses.of(Payment)`:
  - PROCESSING → 202 `PaymentView`;
  - SUCCEEDED | REFUND_PENDING | REFUNDED → 200 `PaymentView`;
  - FAILED with reason `DECLINED` → 402 ProblemDetail `DECLINED` with property `paymentId`;
  - FAILED with any other reason → 503 `DEPENDENCY_UNAVAILABLE` with property `paymentId`.
- Errors before any row is written (not stored, so a replay re-validates):
  - 404 `NOT_FOUND`: unknown booking, or a booking owned by another user (no existence leak);
  - 409 `INVALID_STATE`: booking not PENDING_PAYMENT, or `expiresAt <= now`;
  - 422 `IDEMPOTENCY_KEY_REUSED`: same key with a different hash;
  - 409 `PAYMENT_IN_PROGRESS_OR_DONE` with property `paymentId` = the active attempt's id;
  - 503 `DEPENDENCY_UNAVAILABLE`: booking-service unreachable.
- `GET /api/payments/{id}` → owner only (otherwise 404) → `PaymentView`, always 200.
- `BookingClient.get(UUID bookingId): BookingLookup`, where `sealed interface BookingLookup permits Found(UUID id, long userId, String status, long amountCents, Instant expiresAt), NotFound, Unavailable`. It calls `GET /internal/bookings/{id}` (plan 3 contract: `{id, userId, eventId, status, amountCents, expiresAt, paymentId}`).
- `ProviderClient.charge(UUID paymentId, long amountCents, UUID bookingId): ChargeOutcome`, where `sealed interface ChargeOutcome permits Approved(String chargeId), Declined, Ambiguous(String reason), NotSent(String reason)`. Classify `NotSent` from the `ResourceAccessException` cause: `java.net.ConnectException` / `HttpConnectTimeoutException`. Everything else that is not 2xx is `Ambiguous`.
- `PaymentRepository`:
  - `findByIdempotency(long userId, String key): Optional<Payment>`;
  - `findActiveByBooking(UUID bookingId): Optional<Payment>`;
  - `insertProcessing(Payment)`, which throws `DuplicateKeyException`;
  - `transition(UUID id, PaymentStatus from, PaymentStatus to, String providerRef, String reason): boolean`;
  - `findById(UUID)`.
- `PaymentService.pay` order (spec §8.2):
  1. Idempotency lookup → replay or 422.
  2. Booking lookup and checks.
  3. tx: insert PROCESSING. On `DuplicateKeyException`, re-run the idempotency lookup first (a same-key twin → replay it); otherwise `findActiveByBooking` → 409 with its id.
  4. Charge (no tx).
  5. tx: transition by outcome (+ outbox `payment.succeeded {paymentId, bookingId, amountCents}` on SUCCEEDED).
  6. `PaymentResponses.of(reloaded row)`.

`FakeProvider` (test, MockWebServer dispatcher) mimics Task 1's contract with switchable modes `APPROVE | DECLINE | TIMEOUT_AFTER_CHARGE (stores, then delays 4 s) | ERROR_500 | REFUSE` and a thread-safe `chargeCount()`. `REFUSE` = `shutdown()` the server, so the port refuses connections. `FakeBookingService` serves `/internal/bookings/{id}` from a map the test fills and records whether the service token was sent.

- [x] **Step 1: Failing tests** `config/ClientConfigTest` (copy booking's) + `PayIT`:
  - happy path → 200 SUCCEEDED; row SUCCEEDED with `provider_ref`; exactly one outbox row `payment.succeeded` with payload `{paymentId, bookingId, amountCents}`; the provider saw `Idempotency-Key` = paymentId and the booking amount;
  - replay → 200, the same id, provider `chargeCount()` still 1; same key with another bookingId → 422;
  - DECLINE → 402 `DECLINED` with `paymentId`; row FAILED/`DECLINED`; then a **new key** on the same booking → 200 SUCCEEDED (slot freed);
  - **ambiguous (Review Focus 2):** TIMEOUT_AFTER_CHARGE → 202 PROCESSING within ~3.5 s; row PROCESSING; no outbox row; replay → 202 with the same id and `chargeCount()` 1; a new key → 409 `PAYMENT_IN_PROGRESS_OR_DONE` with that id;
  - ERROR_500 → 202 PROCESSING; REFUSE → 503, row FAILED/`PROVIDER_UNAVAILABLE`; a second request with a new key also gets 503 (not 409) and leaves a second FAILED row, proving the slot was freed (Review Focus 4);
  - booking CONFIRMED → 409 `INVALID_STATE`; booking expired (`expiresAt` = now − 1 h) → 409 `INVALID_STATE`; another user's booking → 404; unknown booking → 404; booking-service down → 503 and no row;
  - missing key → 400; no token → 401; `GET /api/payments/{id}` by another user → 404;
  - `uk_payment_active_booking` at the SQL level: insert a FAILED and then a PROCESSING row for one booking → OK; a second PROCESSING row → `DuplicateKeyException`.
- [x] **Step 2: Failing tests** `PaymentConcurrencyIT` (virtual threads, MockMvc):
  - **proof 6 (Review Focus 1):** 20 parallel `POST /api/payments` for one booking, one user, 20 different keys → `chargeCount()` == 1; exactly 1 row not FAILED; the rest 409 `PAYMENT_IN_PROGRESS_OR_DONE`, each carrying the winner's id;
  - **double-click (Review Focus 3):** 20 parallel requests with the **same** key → exactly 1 row, `chargeCount()` == 1, every 200/202 response has that id, and no 409/500.
- [x] **Step 3:** Run `./mvnw -pl payment-service -am verify` → FAIL.
- [x] **Step 4:** Implement.
- [x] **Step 5:** Run → PASS.

### Task 3: outbox relay + refund consumer

**Interfaces:**
- `OutboxRelay.publishBatch(): int` (same as booking's).
- Topic `payment.refund.requested`, payload `{paymentId, bookingId, reason}` (plan 3 contract).
- `RefundHandler.handle(UUID messageId, UUID paymentId)`:
  1. tx#1: insert into `processed_events` (duplicate → return), then `SUCCEEDED → REFUND_PENDING`. 0 rows (unknown, FAILED, or already refunding) → log and return.
  2. Only if the transition won: `ProviderClient.refund(UUID paymentId, String chargeId): RefundOutcome` (`sealed … permits Refunded(String refundId), Failed(String reason)`) with `Idempotency-Key: refund-<paymentId>`, no tx.
  3. tx#2: `REFUND_PENDING → REFUNDED`. A provider failure leaves it REFUND_PENDING (the plan 5 reconciler retries).
- The listener parses the envelope like booking's `PaymentSucceededListener`. Malformed → `IllegalArgumentException` → DLT.

- [x] **Step 1: Failing tests** `OutboxIT` + `RefundIT`:
  - after a successful pay, the relay publishes `payment.succeeded` on Kafka with key = bookingId and envelope `eventId` = outbox id; the row is marked published;
  - a refund message (sent to real Kafka) for a SUCCEEDED payment → REFUNDED within 10 s; the fake provider saw `Idempotency-Key: refund-<paymentId>` and the stored `chargeId`;
  - **Review Focus 5:** the same message twice, and `handle()` called from 10 threads with distinct message ids for one payment → exactly 1 provider refund call, status REFUNDED;
  - provider refund 500 → REFUND_PENDING stays (not FAILED, not REFUNDED);
  - refund for a FAILED payment → no provider call, status unchanged;
  - a malformed message → appears on `payment.refund.requested.DLT`.
- [x] **Step 2:** Run → FAIL.
- [x] **Step 3:** Implement.
- [x] **Step 4:** Run → PASS.

### Task 4: Compose + end-to-end smoke

- [x] Dockerfiles for both modules (copy booking's; ports 8085 / 8090; the provider image has no DB/Kafka env).
- [x] Compose:
  - `fake-payment-provider` with the `PROVIDER_*` env defaults above, a healthcheck on `/actuator/health`, `mem_limit: 256m`, and no published port.
  - `payment-service` with `DB_HOST`, `PAYMENT_DB_PASSWORD`, `KAFKA_BOOTSTRAP`, `INTERNAL_JWT_SECRET`, `EUREKA_URL`, `JWT_PUBLIC_KEY_FILE` (public key folder only), and `PAYMENT_PROVIDER_URL=http://fake-payment-provider:8090`. It depends on mysql, kafka, discovery-server and fake-payment-provider being healthy, has a healthcheck on 8085, and no published port.
- [x] Extend both smoke scripts, continuing after the booking section, on a fresh seat of the smoke event:
  1. Book seat 2 → 201.
  2. `POST /api/payments` → 200 SUCCEEDED.
  3. Replay with the same key → 200, same id.
  4. A new key on the same booking → 409.
  5. Within 20 s, `GET /api/bookings/{id}` shows CONFIRMED and the seat map shows the seat BOOKED (outbox → Kafka → booking → outbox → Kafka → event).
  6. Port 8085 not published.
  7. Eureka lists PAYMENT-SERVICE.
- [x] Verify:
  - `docker compose up -d --build --wait` → both smoke scripts pass.
  - Stack stopped (`docker compose stop`) → `./mvnw verify` is green.
  - Update the roadmap row 4 to Done.
