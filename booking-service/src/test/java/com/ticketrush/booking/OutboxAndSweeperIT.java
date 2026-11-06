package com.ticketrush.booking;

import com.ticketrush.booking.jobs.BookingSweeper;
import com.ticketrush.booking.outbox.OutboxRelay;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class OutboxAndSweeperIT extends IntegrationTestBase {

    @Autowired
    OutboxRelay relay;
    @Autowired
    BookingSweeper sweeper;
    @Autowired
    StringRedisTemplate redis;

    record Created(long user, long event, String id) {}

    Created pending(long seat) throws Exception {
        long u = uniqueUser(), e = uniqueEvent();
        String id = bodyOf(book(u, "k-" + UUID.randomUUID(), e, List.of(seat))).get("id").asString();
        return new Created(u, e, id);
    }

    /** An hour back, not a second: the Docker Desktop VM clock can step backwards by seconds when it resyncs. */
    void makeOverdue(String id) {
        jdbc.update("UPDATE bookings SET expires_at = NOW(3) - INTERVAL 1 HOUR WHERE id = UUID_TO_BIN(?)", id);
    }

    KafkaConsumer<String, String> consumer(String topic) {
        var c = new KafkaConsumer<String, String>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"), new StringDeserializer(), new StringDeserializer());
        c.subscribe(List.of(topic));
        return c;
    }

    @Test
    void relayPublishesOutboxRowsWithBookingKeyAndOutboxIdAsEventId() throws Exception {
        Created b = pending(1L);
        mvc.perform(post("/api/bookings/" + b.id() + "/cancel").header(AUTHORIZATION, userToken(b.user())));
        String outboxId = jdbc.queryForObject("SELECT BIN_TO_UUID(id) FROM outbox_events WHERE aggregate_id = UUID_TO_BIN(?)", String.class, b.id());
        relay.publishBatch();
        assertThat(jdbc.queryForObject("SELECT published_at IS NOT NULL FROM outbox_events WHERE id = UUID_TO_BIN(?)", Boolean.class, outboxId)).isTrue();
        try (var c = consumer("booking.cancelled")) {
            boolean found = false;
            for (int i = 0; i < 40 && !found; i++) {
                for (var r : c.poll(Duration.ofMillis(500))) {
                    if (b.id().equals(r.key())) {
                        var env = json.readTree(r.value());
                        assertThat(env.get("eventId").asString()).isEqualTo(outboxId);
                        assertThat(env.get("payload").get("bookingId").asString()).isEqualTo(b.id());
                        assertThat(env.get("payload").get("eventId").asLong()).isEqualTo(b.event());
                        found = true;
                    }
                }
            }
            assertThat(found).isTrue();
        }
    }

    @Test
    void twoRelaysInParallelPublishEveryRowExactlyOnceToTheTable() throws Exception {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            Created b = pending(1000L + i);
            mvc.perform(post("/api/bookings/" + b.id() + "/cancel").header(AUTHORIZATION, userToken(b.user())));
            ids.add(b.id());
        }
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            List<Future<Integer>> f = new ArrayList<>();
            for (int i = 0; i < 2; i++) f.add(pool.submit(() -> { start.await(); int n = 0, k; while ((k = relay.publishBatch()) > 0) n += k; return n; }));
            start.countDown();
            for (var x : f) x.get();
        }
        Integer unpublished = jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE published_at IS NULL", Integer.class);
        assertThat(unpublished).isZero();
        Set<String> seen = new HashSet<>();
        try (var c = consumer("booking.cancelled")) {
            for (int i = 0; i < 40 && !seen.containsAll(ids); i++) c.poll(Duration.ofMillis(500)).forEach(r -> seen.add(r.key()));
        }
        assertThat(seen).containsAll(ids);
    }

    @Test
    void sweeperExpiresOverduePendingBookingsOnly() throws Exception {
        Created overdue = pending(1L);
        Created fresh = pending(2L);
        makeOverdue(overdue.id());
        sweeper.sweep();
        assertThat(bookingStatus(overdue.id())).isEqualTo("EXPIRED");
        assertThat(quota(overdue.user(), overdue.event())).isZero();
        assertThat(outboxRows(overdue.id(), "booking.cancelled")).isEqualTo(1);
        assertThat(redis.hasKey("bk:seatlock:" + overdue.event() + ":1")).isFalse();
        assertThat(bookingStatus(fresh.id())).isEqualTo("PENDING_PAYMENT");
        assertThat(outboxRows(fresh.id(), "booking.cancelled")).isZero();
    }

    @Test
    void sweeperFailsStuckHoldingBookings() throws Exception {
        long u = uniqueUser(), e = uniqueEvent();
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO bookings (id, user_id, event_id, seat_count, status, idempotency_key, request_hash, expires_at, created_at, updated_at) "
                + "VALUES (UUID_TO_BIN(?), ?, ?, 1, 'HOLDING', 'stuck', REPEAT('a',64), NOW(3) - INTERVAL 1 HOUR, NOW(3), NOW(3))", id, u, e);
        jdbc.update("INSERT INTO booking_seats (booking_id, seat_id) VALUES (UUID_TO_BIN(?), 9)", id);
        jdbc.update("INSERT INTO user_event_quota (user_id, event_id, seats_reserved) VALUES (?, ?, 1)", u, e);
        sweeper.sweep();
        assertThat(bookingStatus(id)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT failure_reason FROM bookings WHERE id=UUID_TO_BIN(?)", String.class, id)).isEqualTo("HOLDING_TIMEOUT");
        assertThat(quota(u, e)).isZero();
        assertThat(outboxRows(id, "booking.cancelled")).isEqualTo(1);
    }

    @Test
    void twoSweepersInParallelExpireEachBookingOnce() throws Exception {
        List<Created> all = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            Created b = pending(5000L + i);
            makeOverdue(b.id());
            all.add(b);
        }
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            List<Future<Integer>> f = new ArrayList<>();
            for (int i = 0; i < 2; i++) f.add(pool.submit(() -> { start.await(); int n = 0, k; while ((k = sweeper.sweep()) > 0) n += k; return n; }));
            start.countDown();
            int total = 0;
            for (var x : f) total += x.get();
            assertThat(total).isEqualTo(50);
        }
        for (Created b : all) {
            assertThat(bookingStatus(b.id())).isEqualTo("EXPIRED");
            assertThat(outboxRows(b.id(), "booking.cancelled")).isEqualTo(1);
            assertThat(quota(b.user(), b.event())).isZero();
        }
    }
}
