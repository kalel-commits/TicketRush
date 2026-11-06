package com.ticketrush.booking;

import com.ticketrush.booking.jobs.BookingSweeper;
import com.ticketrush.booking.messaging.PaymentSucceededHandler;
import com.ticketrush.common.id.UuidV7;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class PaymentSucceededIT extends IntegrationTestBase {

    @Autowired
    PaymentSucceededHandler handler;
    @Autowired
    BookingSweeper sweeper;
    @Autowired
    KafkaTemplate<String, String> kafka;

    record Created(long user, long event, UUID id) {}

    Created pending(long seat) throws Exception {
        long u = uniqueUser(), e = uniqueEvent();
        String id = bodyOf(book(u, "k-" + UUID.randomUUID(), e, List.of(seat))).get("id").asString();
        return new Created(u, e, UUID.fromString(id));
    }

    @Test
    void paymentConfirmsAPendingBookingOnce() throws Exception {
        Created b = pending(1L);
        UUID payment = UuidV7.next(), msg = UuidV7.next();
        handler.handle(msg, payment, b.id());
        handler.handle(msg, payment, b.id());                       // duplicate delivery
        assertThat(bookingStatus(b.id().toString())).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT BIN_TO_UUID(payment_id) FROM bookings WHERE id=UUID_TO_BIN(?)", String.class, b.id().toString()))
                .isEqualTo(payment.toString());
        assertThat(outboxRows(b.id().toString(), "booking.confirmed")).isEqualTo(1);
        assertThat(outboxRows(b.id().toString(), "payment.refund.requested")).isZero();
        assertThat(quota(b.user(), b.event())).isEqualTo(1);        // confirmed seats keep counting
        handler.handle(UuidV7.next(), payment, b.id());             // same payment, new message id: still a no-op
        assertThat(outboxRows(b.id().toString(), "payment.refund.requested")).isZero();
    }

    @Test
    void latePaymentForAnExpiredBookingIsRefunded() throws Exception {
        Created b = pending(2L);
        jdbc.update("UPDATE bookings SET expires_at = NOW(3) - INTERVAL 1 HOUR WHERE id=UUID_TO_BIN(?)", b.id().toString());
        handler.handle(UuidV7.next(), UuidV7.next(), b.id());
        assertThat(bookingStatus(b.id().toString())).isEqualTo("PENDING_PAYMENT");   // the sweeper owns expiry
        assertThat(outboxRows(b.id().toString(), "payment.refund.requested")).isEqualTo(1);
        assertThat(outboxRows(b.id().toString(), "booking.confirmed")).isZero();
    }

    @Test
    void paymentForACancelledBookingIsRefunded() throws Exception {
        Created b = pending(3L);
        mvc.perform(post("/api/bookings/" + b.id() + "/cancel").header(AUTHORIZATION, userToken(b.user())));
        handler.handle(UuidV7.next(), UuidV7.next(), b.id());
        assertThat(bookingStatus(b.id().toString())).isEqualTo("CANCELLED");
        assertThat(outboxRows(b.id().toString(), "payment.refund.requested")).isEqualTo(1);
    }

    @Test
    void sweeperAndPaymentRaceEndsInExactlyOneOutcome() throws Exception {
        List<Created> all = new ArrayList<>();
        for (int i = 0; i < 20; i++) all.add(pending(700L + i));
        for (Created b : all) {
            jdbc.update("UPDATE bookings SET expires_at = NOW(3) + INTERVAL 200000 MICROSECOND WHERE id=UUID_TO_BIN(?)", b.id().toString());
        }
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Created b : all) {
                pool.submit(() -> { start.await(); Thread.sleep(ThreadLocalRandom.current().nextInt(0, 400)); handler.handle(UuidV7.next(), UuidV7.next(), b.id()); return null; });
                pool.submit(() -> { start.await(); Instant end = Instant.now().plusSeconds(2); while (Instant.now().isBefore(end)) { sweeper.sweep(); Thread.sleep(20); } return null; });
            }
            start.countDown();
        }
        for (Created b : all) {
            String id = b.id().toString();
            String s = bookingStatus(id);
            int refunds = outboxRows(id, "payment.refund.requested");
            int confirmed = outboxRows(id, "booking.confirmed");
            int cancelled = outboxRows(id, "booking.cancelled");
            if (s.equals("CONFIRMED")) {
                assertThat(refunds).as(id).isZero();
                assertThat(confirmed).as(id).isEqualTo(1);
                assertThat(cancelled).as(id).isZero();
            } else {
                assertThat(s).as(id).isEqualTo("EXPIRED");
                assertThat(refunds).as(id).isEqualTo(1);
                assertThat(confirmed).as(id).isZero();
                assertThat(cancelled).as(id).isEqualTo(1);
            }
        }
    }

    /** Spec §16.1 duplicate delivery + the consumer half of the outbox-kill proof: same eventId twice, one effect. */
    @Test
    void sameEventIdTwiceConfirmsOnce() throws Exception {
        Created b = pending(5L);
        UUID payment = UuidV7.next();
        UUID eventId = UuidV7.next();
        String value = json.writeValueAsString(Map.of("eventId", eventId.toString(), "eventType", "payment.succeeded",
                "schemaVersion", 1, "occurredAt", Instant.now().toString(), "aggregateId", b.id().toString(),
                "payload", Map.of("paymentId", payment.toString(), "bookingId", b.id().toString(), "amountCents", 2500)));
        kafka.send("payment.succeeded", b.id().toString(), value).get();
        kafka.send("payment.succeeded", b.id().toString(), value).get();   // the relay re-sent it after a crash

        await().atMost(Duration.ofSeconds(20)).until(() -> "CONFIRMED".equals(bookingStatus(b.id().toString())));
        UUID marker = UuidV7.next();   // a later message on the same key (same partition) proves the duplicate was consumed
        kafka.send("payment.succeeded", b.id().toString(), value.replace(eventId.toString(), marker.toString())).get();
        await().atMost(Duration.ofSeconds(20)).until(() -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM processed_events WHERE event_id=UUID_TO_BIN(?)", Integer.class, marker.toString()) == 1);

        assertThat(outboxRows(b.id().toString(), "booking.confirmed")).isEqualTo(1);
        assertThat(outboxRows(b.id().toString(), "payment.refund.requested")).isZero();
    }

    @Test
    void consumesPaymentSucceededFromKafka() throws Exception {
        Created b = pending(4L);
        UUID payment = UuidV7.next();
        String value = json.writeValueAsString(Map.of("eventId", UuidV7.next().toString(), "eventType", "payment.succeeded",
                "schemaVersion", 1, "occurredAt", Instant.now().toString(), "aggregateId", b.id().toString(),
                "payload", Map.of("paymentId", payment.toString(), "bookingId", b.id().toString(), "amountCents", 2500)));
        kafka.send("payment.succeeded", b.id().toString(), value).get();
        await().atMost(Duration.ofSeconds(20)).until(() -> "CONFIRMED".equals(bookingStatus(b.id().toString())));
    }
}
