package com.ticketrush.payment;

import com.ticketrush.common.id.UuidV7;
import com.ticketrush.common.security.ServiceTokens;
import com.ticketrush.payment.messaging.RefundHandler;
import com.ticketrush.payment.reconcile.PaymentReconciler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Spec §10 payment reconciler (a)(b)(c) and the reconciliation report. */
class PaymentReconcilerIT extends IntegrationTestBase {

    @Autowired
    PaymentReconciler reconciler;
    @Autowired
    RefundHandler refunds;

    record Row(UUID id, UUID bookingId) {}

    /** A payment row created an hour ago (DB clock), as if its request had been lost. */
    Row insert(String status, String providerRef) {
        Row r = new Row(UuidV7.next(), UUID.randomUUID());
        jdbc.update("INSERT INTO payments (id, booking_id, user_id, amount_cents, status, idempotency_key, request_hash, provider_ref, "
                        + "created_at, updated_at) VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), 42, ?, ?, ?, REPEAT('a',64), ?, "
                        + "NOW(3) - INTERVAL 1 HOUR, NOW(3) - INTERVAL 1 HOUR)",
                r.id().toString(), r.bookingId().toString(), PRICE, status, "k-" + UUID.randomUUID(), providerRef);
        return r;
    }

    void age(Row r) {
        jdbc.update("UPDATE payments SET updated_at = NOW(3) - INTERVAL 1 HOUR WHERE id = UUID_TO_BIN(?)", r.id().toString());
    }

    String charged(Row r, String status) {
        PROVIDER.storeCharge(r.id(), PRICE, status);
        return PROVIDER.chargeFor(r.id()).chargeId();
    }

    long refundCallsFor(Row r) {
        return PROVIDER.refundKeys.stream().filter(k -> k.equals("refund-" + r.id())).count();
    }

    int lookupAttempts(Row r) {
        return jdbc.queryForObject("SELECT lookup_attempts FROM payments WHERE id = UUID_TO_BIN(?)", Integer.class, r.id().toString());
    }

    // ---------- (a) PROCESSING ----------

    @Test
    void processingWithAChargeAtTheProviderSucceedsExactlyOnce() {
        Row r = insert("PROCESSING", null);
        String chargeId = charged(r, "SUCCEEDED");

        reconciler.reconcile();
        reconciler.reconcile();

        assertThat(paymentStatus(r.id().toString())).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT provider_ref FROM payments WHERE id = UUID_TO_BIN(?)", String.class, r.id().toString()))
                .isEqualTo(chargeId);
        assertThat(outboxRows(r.bookingId(), "payment.succeeded")).isEqualTo(1);
    }

    @Test
    void processingWithoutAChargeFailsOnlyAfterTwoNotFoundAnswers() {
        Row r = insert("PROCESSING", null);

        reconciler.reconcile();
        assertThat(paymentStatus(r.id().toString())).isEqualTo("PROCESSING");
        assertThat(lookupAttempts(r)).isEqualTo(1);

        age(r);   // the second lookup comes >= 30 s after the first (assumption A7)
        reconciler.reconcile();
        assertThat(paymentStatus(r.id().toString())).isEqualTo("FAILED");
        assertThat(failureReason(r.id().toString())).isEqualTo("NOT_CHARGED");
    }

    @Test
    void anUnreachableProviderNeverCountsAsALookup() {
        Row r = insert("PROCESSING", null);
        PROVIDER.lookupFails = true;

        reconciler.reconcile();
        age(r);
        reconciler.reconcile();

        assertThat(paymentStatus(r.id().toString())).isEqualTo("PROCESSING");
        assertThat(lookupAttempts(r)).isZero();
    }

    @Test
    void aDeclinedChargeFailsTheAttempt() {
        Row r = insert("PROCESSING", null);
        charged(r, "DECLINED");

        reconciler.reconcile();

        assertThat(paymentStatus(r.id().toString())).isEqualTo("FAILED");
        assertThat(failureReason(r.id().toString())).isEqualTo("DECLINED");
    }

    // ---------- (b) SUCCEEDED, not yet verified ----------

    @Test
    void succeededAndConfirmedByThisPaymentIsVerified() {
        Row r = insert("SUCCEEDED", "ch_verify");
        BOOKINGS.status(r.bookingId(), "CONFIRMED", r.id());

        reconciler.reconcile();

        assertThat(jdbc.queryForObject("SELECT verified_at IS NOT NULL FROM payments WHERE id = UUID_TO_BIN(?)", Boolean.class,
                r.id().toString())).isTrue();
        assertThat(paymentStatus(r.id().toString())).isEqualTo("SUCCEEDED");
    }

    @Test
    void succeededForABookingThatEndedOrWasPaidByAnotherPaymentIsRefunded() {
        Row expired = insert("SUCCEEDED", "ch_expired");
        Row other = insert("SUCCEEDED", "ch_other");
        BOOKINGS.status(expired.bookingId(), "EXPIRED", null);
        BOOKINGS.status(other.bookingId(), "CONFIRMED", UUID.randomUUID());

        reconciler.reconcile();

        assertThat(paymentStatus(expired.id().toString())).isEqualTo("REFUNDED");
        assertThat(paymentStatus(other.id().toString())).isEqualTo("REFUNDED");
        assertThat(refundCallsFor(expired)).isEqualTo(1);
        assertThat(refundCallsFor(other)).isEqualTo(1);
    }

    @Test
    void succeededWaitsWhileTheBookingAwaitsConfirmationOrBookingServiceIsDown() {
        Row pending = insert("SUCCEEDED", "ch_p");
        BOOKINGS.status(pending.bookingId(), "PENDING_PAYMENT", null);
        reconciler.reconcile();
        assertThat(paymentStatus(pending.id().toString())).isEqualTo("SUCCEEDED");

        Row ended = insert("SUCCEEDED", "ch_e");
        BOOKINGS.status(ended.bookingId(), "EXPIRED", null);
        BOOKINGS.failing = true;
        reconciler.reconcile();
        assertThat(paymentStatus(ended.id().toString())).isEqualTo("SUCCEEDED");
        assertThat(refundCallsFor(ended)).isZero();
    }

    /** Review Focus 3: the reconciler and the Kafka refund path race on the same payments. */
    @Test
    void reconcilerAndRefundConsumerRefundEachPaymentOnce() throws Exception {
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Row r = insert("SUCCEEDED", "ch_race_" + i);
            BOOKINGS.status(r.bookingId(), "EXPIRED", null);
            rows.add(r);
        }
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            futures.add(pool.submit(() -> { start.await(); return reconciler.reconcile(); }));
            for (Row r : rows) futures.add(pool.submit(() -> { start.await(); return refunds.handle(UuidV7.next(), r.id()); }));
            start.countDown();
        }
        for (var f : futures) f.get();

        for (Row r : rows) {
            assertThat(paymentStatus(r.id().toString())).isEqualTo("REFUNDED");
            assertThat(refundCallsFor(r)).as("refund calls for %s", r.id()).isEqualTo(1);
        }
    }

    // ---------- (c) REFUND_PENDING ----------

    @Test
    void aFailedRefundIsRetriedWithTheSameKey() {
        Row r = insert("REFUND_PENDING", "ch_retry");

        reconciler.reconcile();

        assertThat(paymentStatus(r.id().toString())).isEqualTo("REFUNDED");
        assertThat(PROVIDER.refundKeys).contains("refund-" + r.id());
        assertThat(PROVIDER.refundedChargeIds).contains("ch_retry");
    }

    // ---------- report ----------

    JsonNode report() throws Exception {
        String token = "Bearer " + ServiceTokens.provider(INTERNAL_SECRET, "ops", Clock.systemUTC()).token();
        return bodyOf(mvc.perform(get("/internal/payments/reconciliation-report").header(AUTHORIZATION, token)).andExpect(status().isOk()));
    }

    @Test
    void reportMatchesProviderAndLocalStateAndFlagsMismatches() throws Exception {
        Row ok = insert("SUCCEEDED", null);
        String okCharge = charged(ok, "SUCCEEDED");
        jdbc.update("UPDATE payments SET provider_ref = ? WHERE id = UUID_TO_BIN(?)", okCharge, ok.id().toString());
        Row refunded = insert("REFUNDED", null);
        PROVIDER.refundedChargeIds.add(charged(refunded, "SUCCEEDED"));
        Row declined = insert("FAILED", null);
        charged(declined, "DECLINED");

        JsonNode clean = report();
        assertThat(clean.get("mismatches").size()).isZero();
        assertThat(clean.get("providerCharges").asInt()).isEqualTo(3);

        jdbc.update("UPDATE payments SET status = 'FAILED' WHERE id = UUID_TO_BIN(?)", ok.id().toString());   // behind the provider's back
        JsonNode dirty = report();
        assertThat(dirty.get("mismatches").size()).isEqualTo(1);
        assertThat(dirty.get("mismatches").get(0).get("paymentId").asString()).isEqualTo(ok.id().toString());
    }

    @Test
    void reportRequiresAServiceToken() throws Exception {
        mvc.perform(get("/internal/payments/reconciliation-report").header(AUTHORIZATION, userToken(1))).andExpect(status().isUnauthorized());
    }

    // ---------- timestamps ----------

    @Test
    void attemptsAreTimestampedWithTheDatabaseClock() throws Exception {
        long u = uniqueUser();
        String id = bodyOf(pay(u, "k-ts", pendingBooking(u))).get("id").asString();
        long ageMicros = jdbc.queryForObject("SELECT TIMESTAMPDIFF(MICROSECOND, created_at, NOW(3)) FROM payments WHERE id = UUID_TO_BIN(?)",
                Long.class, id);
        assertThat(ageMicros).as("created_at must not be in the database's future").isBetween(0L, 5_000_000L);
    }
}
