# TicketRush Plan 5 — Reliability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** the system heals itself. Calls to sick dependencies fail fast instead of piling up. Seats stuck HELD because a message was lost get fixed by asking the owner. Ambiguous payments get resolved against the provider. Refunds that failed get retried. Dead-lettered messages are visible. Old outbox/dedupe rows don't grow forever.

**Architecture:** no new services.
- **Resilience4j core** (`resilience4j-circuitbreaker`, `-retry`, `-micrometer`) is wired programmatically in a small `ResilienceConfig` per service, not with annotations. The outcomes are sealed *result* types rather than exceptions, so breakers use `recordResult` predicates.
- **event-service** gets its first outbound client (booking-service status batch) and a **seat reconciler**.
- **payment-service** gets the **payment reconciler** (a)(b)(c) and the reconciliation report.
- booking, payment and event each get a DLT logger and a cleanup job.

**Tech Stack:** plans 1–4, plus `io.github.resilience4j:resilience4j-circuitbreaker`, `resilience4j-retry` and `resilience4j-micrometer` (version from the existing `resilience4j-bom` 2.4.0 in the root pom).

**Spec:** `docs/superpowers/specs/2026-11-01-ticketrush-design.md`: §10 (reconciler + report), §11.5 (DLT), §12 (jobs), §14 (resilience table), §17 (failure scenarios). **Roadmap:** plan 5. "Done when": proof 5, the duplicate-delivery tests and the outbox-kill test pass. Proof 5 (sweeper vs `payment.succeeded`) already passes from plan 3 (`PaymentSucceededIT.sweeperAndPaymentRaceEndsInExactlyOneOutcome`) and must stay green.

## Global Constraints

- Plan 1–4 constraints apply, including **no DB transaction spans an HTTP call or a Kafka send** (the outbox relay excepted), conditional `UPDATE … WHERE status=<from>` for every transition, and test timestamps backdated by ≥ 1 hour.
- Jobs that call another service **do not hold row locks across the call**. They select candidates without locking, call the other service, then apply each change as its own conditional update, so a concurrent actor (consumer, sweeper, user) simply makes the update match 0 rows. (Spec §12's `SKIP LOCKED` pattern stays for the jobs that make no remote call: sweeper, relay, cleanup.)
- Resilience settings (spec §14), as properties under `ticketrush.resilience.<name>.*` with these defaults:
  - **Breakers:** failure rate 50 % over a count-based window of 20 calls, `minimumNumberOfCalls` 10, open 10 s, 3 calls in half-open.
  - **Retries:** max 3 attempts (= 1 + "×2"), wait 100 ms. **Retry wraps CircuitBreaker.**
- Breaker names: `eventHold` (booking), `redis` (booking), `bookingLookup` (payment), `provider` (payment), `bookingStatus` (event and payment reconcilers). Each is registered in `TaggedCircuitBreakerMetrics`, so `resilience4j_circuitbreaker_state` shows up in `/actuator/metrics`.
- Every **new** RestClient follows plan 3's rule: a plain `@Primary @Scope("prototype")` builder next to the `@LoadBalanced` one, plus a `ClientConfigTest`. Any client boundary catches `RuntimeException` (plan 4 lesson).
- New metrics (Micrometer counters):
  - `reconciler.seats{action=booked|released}`;
  - `reconciler.payments{action=succeeded|failed|verified|refund_requested|refunded}`;
  - `kafka.dlt.messages{topic}`.
- No commits unless the user authorizes them.

## Review Focus

1. **A seat whose booking was CONFIRMED but whose `booking.confirmed` message was lost.** The seat reconciler books it and never releases it. A seat re-held by a *new* booking is never touched by a reconcile of the old one. Test in Task 2.
2. **The provider charged, but the payment row is still PROCESSING.** The reconciler finds the charge → SUCCEEDED + `payment.succeeded` exactly once. One "not found" answer, or an unreachable provider, never fails the payment (A7 requires two not-found answers ≥ 30 s apart). Test in Task 3.
3. **A payment SUCCEEDED for a booking that ended EXPIRED/CANCELLED, while the booking-side refund message is also in flight.** Exactly one provider refund (the conditional `SUCCEEDED → REFUND_PENDING` picks the winner). Test in Task 3.
4. **A dependency fails hard.** After the breaker opens, calls fail in < 50 ms without touching the dependency. A booking under an open `eventHold` breaker is FAILED with quota released and **no** `booking.cancelled` row (nothing was held). A payment under an open `provider` breaker is FAILED/`PROVIDER_UNAVAILABLE` → 503. Test in Task 1.
5. **The outbox relay crashes after the Kafka send but before marking the row.** The message is published twice with the same `eventId`, and the consumer applies it once. Test in Task 4.

---

## File Structure

```
booking-service/
  pom.xml                                  + resilience4j-circuitbreaker, -retry, -micrometer
  config/ResilienceConfig.java             registries + named breakers/retries from properties, metrics binding
  eventclient/EventClient.java             wrap hold() in Retry(CircuitBreaker); new HoldOutcome.NotSent
  eventclient/HoldOutcome.java             + NotSent(String reason)
  api/BookingService.java                  NotSent -> FAILED, quota released, no outbox, 503
  seatlock/SeatLocks.java                  acquire/release through the "redis" breaker (open -> skip filter)
  jobs/OutboxCleanup.java                  daily: published outbox rows + processed_events older than 7 days
  messaging/DeadLetterLogger.java          <topic>.DLT listener: ERROR log + kafka.dlt.messages
event-service/
  pom.xml                                  + resilience4j-*
  EventServiceApplication.java             + @EnableScheduling
  config/ClientConfig.java                 @Primary plain builder, @LoadBalanced builder, bookingRestClient, service token
  config/ResilienceConfig.java             "bookingStatus" breaker
  config/KafkaConfig.java                  + NewTopic booking.confirmed.DLT, booking.cancelled.DLT
  reconcile/BookingStatusClient.java       statuses(List<UUID>) -> StatusLookup (sealed: Found(Map<UUID,String>) | Unavailable)
  reconcile/SeatReconciler.java            spec §12 seat reconciler
  jobs/ProcessedEventsCleanup.java, messaging/DeadLetterLogger.java
payment-service/
  pom.xml                                  + resilience4j-*
  config/ResilienceConfig.java             "bookingLookup", "provider", "bookingStatus"
  bookingclient/BookingClient.java         get() through Retry(CB); + statuses(List<UUID>)
  provider/ProviderClient.java             charge() through CB (open -> NotSent("CIRCUIT_OPEN")); + lookup(UUID), listCharges()
  domain/PaymentRepository.java            insert uses NOW(3); + reconciler queries; + markVerified, recordNotFound
  api/PaymentService.java                  409 race: retry insert once when no active attempt is found
  messaging/RefundHandler.java             extract refund step for reuse: refundClaimed(Payment) -> Outcome
  reconcile/PaymentReconciler.java         (a) PROCESSING, (b) unverified SUCCEEDED, (c) REFUND_PENDING
  reconcile/ReconciliationController.java  GET /internal/payments/reconciliation-report
  jobs/OutboxCleanup.java, messaging/DeadLetterLogger.java
fake-payment-provider/ChargeStore.java     refund key reused with another charge -> 409; second refund key on a refunded charge -> 409
docs/runbook.md                            DLT inspection + replay commands, reconciler metrics
```

---

### Task 1: Circuit breakers and retries (spec §14)

**Interfaces:**
- `ResilienceConfig` (one per service) exposes `CircuitBreaker` and `Retry` beans by name. Configs are built from `ticketrush.resilience.<name>.{failure-rate,window,min-calls,open-seconds,max-attempts}` with the defaults in Global Constraints.
- **booking → event hold** (`EventClient.hold`), decorated `Retry(CircuitBreaker(call))`:
  - Retry on result `NotSent` (connect failure) or `Ambiguous` caused by 5xx. **Not** on a read timeout, since the hold may still commit. Retrying is safe because event-service holds are idempotent per bookingId.
  - The breaker records `NotSent` and `Ambiguous` as failures.
  - `CallNotPermittedException` → `HoldOutcome.NotSent("CIRCUIT_OPEN")`.
  - To make that distinction, `EventClient` classifies results as `NotSent` (no connection, same `neverConnected` test as payment's ProviderClient) / `Ambiguous(reason, boolean retryable)` (5xx → retryable; read timeout or parse error → not) / `Held` / `Rejected`.
- `BookingService.create`, `NotSent` case → booking FAILED (`EVENT_SERVICE_UNAVAILABLE`), quota released, Redis locks released, **no** outbox row, 503 `DEPENDENCY_UNAVAILABLE`.
- **booking → Redis** (`SeatLocks`): `acquire`/`release` run through breaker `redis`. Open → `acquire` returns true (skip the filter, increment `booking.seatlock.bypassed`) without touching Redis.
- **payment → booking get** (`BookingClient.get`): `Retry(CB)`, retry on result `Unavailable`. Open → `Unavailable("CIRCUIT_OPEN")` → 503.
- **payment → provider charge** (`ProviderClient.charge`): CB only, **no retry**. Records `Ambiguous` and `NotSent` as failures. Open → `NotSent("CIRCUIT_OPEN")` → FAILED/`PROVIDER_UNAVAILABLE`, 503. `refund`/`lookup` are not decorated (the reconciler retries them).
- `PaymentService` 409 race (plan 4 deferred minor): if `DuplicateKeyException` and neither the idempotency twin nor an active attempt is found, retry the insert **once**. Still duplicate → 409 as before.

- [x] **Step 1: Failing tests.**
  - booking `EventClientResilienceTest` (unit, MockWebServer, breaker config: window 4, min calls 2, open 10 s):
    - two 503s then 200 → `Held`, and the server saw 3 requests;
    - read timeout → `Ambiguous` after exactly 1 request (not retried);
    - after enough failures the breaker opens → the next `hold` returns `NotSent("CIRCUIT_OPEN")` in < 50 ms and the server request count is unchanged.
  - booking `BookingCreateIT` + `circuitOpenFailsFastWithoutOutbox`: force-open `eventHold` (`circuitBreaker.transitionToOpenState()`) → 503; booking FAILED; quota 0; zero `booking.cancelled` rows; the fake event-service saw 0 hold calls. Reset the breaker in `@AfterEach`.
  - booking `SeatLocksTest`: force-open `redis` → `acquire` returns true without a Redis call (pause the container and assert latency < 50 ms).
  - payment `PayIT` + `providerCircuitOpenFailsTheAttempt`: force-open `provider` → 503 `DEPENDENCY_UNAVAILABLE` with `paymentId`; row FAILED/`PROVIDER_UNAVAILABLE`; `PROVIDER.chargeCalls == 0`.
  - payment `BookingClientTest` + `retriesTwiceThenUnavailable`: MockWebServer returns 503 three times → `Unavailable` and 3 requests; 503, 503, 200 → `Found`.
  - payment `PaymentServiceTest` (plain unit test, hand-written stub `PaymentRepository` subclass + stub clients) `duplicateWithNoActiveAttemptRetriesInsertOnce`: the first `insertProcessing` throws `DuplicateKeyException` while both lookups are empty → the insert is called a second time and `pay` returns the SUCCEEDED attempt.
- [x] **Step 2:** Run `./mvnw -pl booking-service,payment-service -am verify` → FAIL.
- [x] **Step 3:** Implement.
- [x] **Step 4:** Run → PASS. Existing proofs stay green: plan 3 proofs 1–4 and plan 4 proof 6, since an open breaker must not occur in those tests (they make no failing calls).

### Task 2: Seat reconciler (event-service, spec §12)

**Interfaces:**
- `BookingStatusClient.statuses(List<UUID> bookingIds): StatusLookup` → `POST /internal/bookings/status {bookingIds}` (plan 3 contract, ≤ 200 ids, unknown ids omitted). Service token from `ServiceTokens.provider(secret, "event-service", clock)`. connect 500 ms / read 2 s. Breaker `bookingStatus`. Any failure or open breaker → `Unavailable`.
- `SeatReconciler.reconcile(): Result(int booked, int released)`, `@Scheduled(fixedDelayString = "${ticketrush.reconciler.seat-interval-ms:60000}", initialDelayString = "${ticketrush.reconciler.seat-initial-delay-ms:60000}")`.
  1. `SELECT DISTINCT booking_id, event_id FROM seats WHERE status='HELD' AND held_until < NOW(3) - INTERVAL 2 MINUTE LIMIT 200` (uses `idx_seat_status_held`).
  2. `statuses(ids)`. `Unavailable` → return (0, 0) and log at WARN.
  3. Per booking:
     - `CONFIRMED` → `UPDATE seats SET status='BOOKED', held_until=NULL, updated_at=NOW(3) WHERE booking_id=? AND status='HELD'`;
     - `FAILED|CANCELLED|EXPIRED` or **absent from the answer** → `UPDATE seats SET status='AVAILABLE', booking_id=NULL, held_until=NULL, updated_at=NOW(3) WHERE booking_id=? AND status='HELD'`;
     - `HOLDING|PENDING_PAYMENT` → skip.
  4. Evict `AvailabilityService.evict(eventId)` for each event with a change. Count the changes in `reconciler.seats`.
- `ticketrush.booking-service.base-url: http://booking-service` in event-service's application.yml; Compose already gives event-service `INTERNAL_JWT_SECRET` and Eureka.

- [x] **Step 1: Failing tests** `SeatReconcilerIT` (existing event IntegrationTestBase + a `FakeBookingStatus` MockWebServer registered as the `booking-service` simple-discovery instance; seats made stale with `held_until = NOW(3) - INTERVAL 1 HOUR`):
  - stale HELD seat, booking CONFIRMED → BOOKED, `held_until` NULL, `reconciler.seats{action=booked}` +1;
  - stale HELD, booking EXPIRED → AVAILABLE with `booking_id` NULL; a booking id absent from the answer → AVAILABLE;
  - stale HELD, booking PENDING_PAYMENT → still HELD;
  - **fresh** HELD seat (`held_until` in the future) → untouched, and its booking id was not in the request body;
  - **Review Focus 1:** the old booking's seat was released and re-held by a new booking B2 (fresh) while the old booking B1 (EXPIRED) still has another stale seat → only B1's stale seat is released, and B2's seat stays HELD by B2;
  - booking-service answers 503 → nothing changes, and the result is (0, 0);
  - the request carries `Authorization: Bearer <service token>`;
  - `ClientConfigTest` (event copy) passes.
- [x] **Step 2:** Run `./mvnw -pl event-service -am verify` → FAIL.
- [x] **Step 3:** Implement.
- [x] **Step 4:** Run → PASS.

### Task 3: Payment reconciler + reconciliation report (spec §10)

**Interfaces:**
- `ProviderClient.lookup(UUID paymentId): LookupOutcome` (`sealed … permits Charged(String chargeId), Declined, NotFound, Unavailable(String reason)`) → `GET /charges?idempotencyKey=<id>`. `listCharges(): List<ProviderCharge>` → `GET /charges` (`record ProviderCharge(String chargeId, String idempotencyKey, String status, boolean refunded)`).
- `BookingClient.statuses(List<UUID>): Map<UUID, BookingStatusView>` or `Unavailable` — same contract as event's client, breaker `bookingStatus`.
- `PaymentRepository`:
  - `insertProcessing` now sets `created_at`/`updated_at` with `NOW(3)` (plan 4 deferred minor; the reconciler ages rows by DB time);
  - `findProcessingOlderThan(int seconds, int limit)`;
  - `findUnverifiedSucceededOlderThan(int seconds, int limit)`;
  - `findRefundPendingOlderThan(int seconds, int limit)`;
  - `recordNotFound(UUID id): boolean` (`lookup_attempts = lookup_attempts + 1, updated_at = NOW(3) WHERE status='PROCESSING'`);
  - `failIfNotFoundTwice(UUID id): boolean` (`… SET status='FAILED', failure_reason='NOT_CHARGED' WHERE id=? AND status='PROCESSING' AND lookup_attempts >= 1 AND updated_at <= NOW(3) - INTERVAL 30 SECOND`);
  - `markVerified(UUID id)`.
- `RefundHandler.refundClaimed(Payment p): Outcome` holds the provider-call + `REFUND_PENDING → REFUNDED` step, extracted so the reconciler reuses it. `handle()` keeps its behaviour.
- `PaymentReconciler.reconcile(): Report(int succeeded, int failed, int verified, int refundsRequested, int refunded)`, `@Scheduled` every `${ticketrush.reconciler.payment-interval-ms:30000}`:
  - **(a)** `PROCESSING` with `updated_at < NOW(3) - 15 s`, through `lookup`:
    - `Charged` → in one tx: `PROCESSING → SUCCEEDED` (+ `provider_ref`) and, only if that won, outbox `payment.succeeded`;
    - `Declined` → FAILED/`DECLINED`;
    - `NotFound` → `failIfNotFoundTwice`, else `recordNotFound`;
    - `Unavailable` → **no change** (it does not count as a lookup).
  - **(b)** `SUCCEEDED AND verified_at IS NULL AND updated_at < NOW(3) - 2 min`, batched through `statuses`:
    - CONFIRMED with `paymentId` == this payment → `markVerified`;
    - CONFIRMED by **another** payment, `FAILED|CANCELLED|EXPIRED`, or unknown booking → conditional `SUCCEEDED → REFUND_PENDING`, then `refundClaimed` if it won;
    - `PENDING_PAYMENT|HOLDING` → wait;
    - `Unavailable` → skip the batch.
  - **(c)** `REFUND_PENDING` with `updated_at < NOW(3) - 30 s` → `refundClaimed` (same `refund-<id>` key, so retries are safe).
- `GET /internal/payments/reconciliation-report` (service token) → `{providerCharges, providerSucceeded, providerRefunded, localSucceeded, localRefundPending, localRefunded, localProcessing, mismatches: [{paymentId, providerStatus, providerRefunded, localStatus}]}`. A **mismatch** is any provider charge whose local row is missing, or whose local status disagrees with the expectation:
  - provider DECLINED → FAILED;
  - SUCCEEDED and not refunded → SUCCEEDED | REFUND_PENDING;
  - SUCCEEDED and refunded → REFUNDED;
  - PROCESSING counts as unresolved, is listed in `localProcessing`, and is not a mismatch.
- Fake provider `ChargeStore.refund`: a key reused with a different `chargeId` → 409; a charge already refunded under another key → 409.

- [x] **Step 1: Failing tests** `PaymentReconcilerIT` (rows aged with `UPDATE payments SET updated_at = NOW(3) - INTERVAL 1 HOUR`; `FakeProvider` gains a `GET /charges?idempotencyKey` handler, a `GET /charges` list, and `lookupFails`; `FakeBookingService` gains `POST /internal/bookings/status`):
  - **Review Focus 2:**
    - a TIMEOUT_AFTER_CHARGE attempt (PROCESSING, charge exists) → `reconcile()` → SUCCEEDED, `provider_ref` = the charge id, exactly 1 `payment.succeeded` outbox row; a second `reconcile()` adds none;
    - an aged PROCESSING row with no charge → the first reconcile leaves PROCESSING with `lookup_attempts` 1; age it again → FAILED/`NOT_CHARGED`;
    - lookup unavailable twice → still PROCESSING and `lookup_attempts` 0;
  - a DECLINED charge found → FAILED/`DECLINED`;
  - (b):
    - booking CONFIRMED with this payment → `verified_at` set;
    - booking EXPIRED → REFUNDED, with exactly 1 provider refund;
    - booking PENDING_PAYMENT → unchanged;
    - booking-service 503 → unchanged;
  - **Review Focus 3:** booking EXPIRED, and `RefundHandler.handle()` (Kafka path) plus `reconcile()` run concurrently 10 times → each payment ends REFUNDED with exactly 1 provider refund call;
  - (c): REFUND_PENDING after a failed refund (`refundsFail`), aged; the provider recovers → `reconcile()` → REFUNDED, and the refund key used was `refund-<paymentId>`;
  - report: one SUCCEEDED, one REFUNDED, one DECLINED → `mismatches` empty. Flip a local row to FAILED behind the provider's back → exactly that payment appears in `mismatches`. Service token required (a user token → 401).
  - `PaymentRepository` insert timestamps equal DB time: `created_at` within 1 s of `NOW(3)` read in the same test.
  - Provider `ProviderControllerTest` + `refundKeyIsBoundToOneCharge`.
- [x] **Step 2:** Run `./mvnw -pl fake-payment-provider,payment-service -am verify` → FAIL.
- [x] **Step 3:** Implement.
- [x] **Step 4:** Run → PASS.

### Task 4: DLT visibility, cleanup jobs, duplicate and outbox-kill proofs, end to end

**Interfaces:**
- `DeadLetterLogger` (booking: `payment.succeeded.DLT`; payment: `payment.refund.requested.DLT`; event: `booking.confirmed.DLT`, `booking.cancelled.DLT`).
  - Consumer group `<service>-dlt`; logs ERROR with topic, key, offset and the `kafka_dlt-exception-message` header.
  - Increments `kafka.dlt.messages{topic}`.
  - Never throws.
- event `KafkaConfig` declares its two DLT topics (1 partition).
- Cleanup, `@Scheduled(cron = "${ticketrush.cleanup.cron:0 30 3 * * *}")`, deleting in batches of 1000 until fewer than 1000 rows are affected:
  - booking/payment `OutboxCleanup.run(): int` deletes `outbox_events WHERE published_at < NOW(3) - INTERVAL 7 DAY`, plus `processed_events WHERE processed_at < NOW(3) - INTERVAL 7 DAY`;
  - event `ProcessedEventsCleanup.run(): int` deletes the latter only.
- `docs/runbook.md` covers:
  - listing DLT messages (`kafka-console-consumer.sh --bootstrap-server kafka:9092 --topic <t>.DLT --from-beginning --property print.key=true --property print.headers=true`, run via `docker compose exec kafka /opt/kafka/bin/...`);
  - replaying one message (`kafka-console-producer.sh … --property parse.key=true --property key.separator=|`), which is safe because consumers dedupe by `eventId`;
  - what each reconciler metric means.

- [x] **Step 1: Failing tests:**
  - each service `DeadLetterIT`: a malformed message → `kafka.dlt.messages{topic=<t>.DLT}` reaches 1 (await 15 s). Use the existing malformed-message tests' setup.
  - `OutboxCleanupIT` (booking, payment) / `ProcessedEventsCleanupIT` (event): rows 8 days old are deleted; 6-day-old and unpublished rows remain.
  - **Duplicate delivery for every event type** (spec §16.1), each sent twice with the same `eventId` over real Kafka → one effect:
    - `booking.confirmed` and `booking.cancelled` (event `BookingEventsIT`; add the cancelled-twice case if missing);
    - `payment.succeeded` (booking `PaymentSucceededIT`, assert one `booking.confirmed` outbox row);
    - `payment.refund.requested` (payment `RefundIT`, exists).
  - **Outbox-kill (Review Focus 5),** payment `OutboxIT` + `relayCrashBeforeMarkPublishesTwiceWithOneEventId`: publish; reset `published_at = NULL` (the crash after send); publish again → Kafka holds 2 records for the booking with the **same** `eventId`. booking `PaymentSucceededIT` + `sameEventIdTwiceConfirmsOnce` proves the consumer side.
- [x] **Step 2:** Run → FAIL.
- [x] **Step 3:** Implement.
- [x] **Step 4:** Run → PASS.
- [x] **Step 5: End to end.** No Compose changes: every new interval and threshold has its default in application.yml.
  - `docker compose up -d --build --wait` → both smoke scripts pass.
  - Manual chaos check, recorded in the ledger:
    - `docker compose stop redis` → a booking through the gateway still returns 201 (filter bypassed);
    - `docker compose start redis`.
  - Stack stopped → `./mvnw verify` green → roadmap row 5 Done.
