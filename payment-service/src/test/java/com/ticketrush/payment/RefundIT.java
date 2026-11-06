package com.ticketrush.payment;

import com.ticketrush.common.id.UuidV7;
import com.ticketrush.payment.messaging.RefundHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** payment.refund.requested consumer (spec §8.4): exactly one provider refund per payment. */
class RefundIT extends IntegrationTestBase {

    @Autowired
    RefundHandler handler;
    @Autowired
    KafkaTemplate<String, String> kafka;

    record Paid(UUID paymentId, UUID bookingId) {}

    Paid succeededPayment() throws Exception {
        long u = uniqueUser();
        UUID b = pendingBooking(u);
        return new Paid(UUID.fromString(bodyOf(pay(u, "k-" + UUID.randomUUID(), b)).get("id").asString()), b);
    }

    String envelope(UUID messageId, Paid p) {
        return """
                {"eventId":"%s","eventType":"payment.refund.requested","schemaVersion":1,"occurredAt":"2026-11-02T10:00:00Z",
                 "aggregateId":"%s","payload":{"paymentId":"%s","bookingId":"%s","reason":"BOOKING_EXPIRED"}}"""
                .formatted(messageId, p.bookingId(), p.paymentId(), p.bookingId());
    }

    @Test
    void refundMessageRefundsTheChargeOnce() throws Exception {
        Paid p = succeededPayment();
        String chargeId = PROVIDER.chargeFor(p.paymentId()).chargeId();
        UUID messageId = UuidV7.next();

        kafka.send("payment.refund.requested", p.bookingId().toString(), envelope(messageId, p)).get();
        kafka.send("payment.refund.requested", p.bookingId().toString(), envelope(messageId, p)).get();   // redelivery

        await().atMost(Duration.ofSeconds(10)).until(() -> "REFUNDED".equals(paymentStatus(p.paymentId().toString())));
        Thread.sleep(500);   // give the duplicate time to be (not) processed
        assertThat(PROVIDER.refundCalls.get()).isEqualTo(1);
        assertThat(PROVIDER.refundKeys).containsExactly("refund-" + p.paymentId());
        assertThat(PROVIDER.refundedChargeIds).containsExactly(chargeId);
    }

    @Test
    void concurrentRefundRequestsCallTheProviderOnce() throws Exception {
        Paid p = succeededPayment();
        CountDownLatch start = new CountDownLatch(1);
        List<Future<RefundHandler.Outcome>> results = new ArrayList<>();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 10; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return handler.handle(UuidV7.next(), p.paymentId());   // distinct message ids: dedupe can't help
                }));
            }
            start.countDown();
        }
        List<RefundHandler.Outcome> outcomes = new ArrayList<>();
        for (var f : results) outcomes.add(f.get());
        assertThat(outcomes).filteredOn(o -> o == RefundHandler.Outcome.REFUNDED).hasSize(1);
        assertThat(PROVIDER.refundCalls.get()).isEqualTo(1);
        assertThat(paymentStatus(p.paymentId().toString())).isEqualTo("REFUNDED");
    }

    @Test
    void providerFailureLeavesRefundPending() throws Exception {
        Paid p = succeededPayment();
        PROVIDER.refundsFail = true;
        assertThat(handler.handle(UuidV7.next(), p.paymentId())).isEqualTo(RefundHandler.Outcome.REFUND_PENDING);
        assertThat(paymentStatus(p.paymentId().toString())).isEqualTo("REFUND_PENDING");
    }

    @Test
    void refundForAFailedPaymentIsIgnored() throws Exception {
        long u = uniqueUser();
        UUID b = pendingBooking(u);
        PROVIDER.mode = FakeProvider.Mode.DECLINE;
        UUID failed = UUID.fromString(bodyOf(pay(u, "k1", b)).get("paymentId").asString());
        assertThat(handler.handle(UuidV7.next(), failed)).isEqualTo(RefundHandler.Outcome.IGNORED);
        assertThat(handler.handle(UuidV7.next(), UUID.randomUUID())).isEqualTo(RefundHandler.Outcome.IGNORED);
        assertThat(PROVIDER.refundCalls.get()).isZero();
        assertThat(paymentStatus(failed.toString())).isEqualTo("FAILED");
    }

    @Test
    void duplicateMessageIdIsDeduplicated() throws Exception {
        Paid p = succeededPayment();
        UUID messageId = UuidV7.next();
        assertThat(handler.handle(messageId, p.paymentId())).isEqualTo(RefundHandler.Outcome.REFUNDED);
        assertThat(handler.handle(messageId, p.paymentId())).isEqualTo(RefundHandler.Outcome.DUPLICATE);
    }

    @Test
    void malformedMessageGoesToTheDlt() throws Exception {
        String key = "bad-" + UUID.randomUUID();
        kafka.send("payment.refund.requested", key, "{not json").get();
        try (var c = OutboxIT.consumer("payment.refund.requested.DLT")) {
            boolean seen = false;
            for (int i = 0; i < 40 && !seen; i++) {
                for (var r : c.poll(Duration.ofMillis(500))) seen |= key.equals(r.key());
            }
            assertThat(seen).isTrue();
        }
    }
}
