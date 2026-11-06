package com.ticketrush.booking;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;

/** Spec §16.1 concurrency proofs 1–4. */
class ConcurrencyProofIT extends IntegrationTestBase {

    @Autowired
    StringRedisTemplate redis;

    record Response(int status, String bookingId) {}

    /** Runs n requests at the same instant on virtual threads. */
    List<Response> burst(int n, IntFunction<Response> request) throws Exception {
        ConcurrentLinkedQueue<Response> out = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < n; i++) {
                int idx = i;
                pool.submit(() -> {
                    try {
                        start.await();
                        out.add(request.apply(idx));
                    } catch (Throwable t) {
                        errors.add(t);
                    }
                });
            }
            start.countDown();
        }
        assertThat(errors).isEmpty();
        return List.copyOf(out);
    }

    Response call(long user, String key, long event, List<Long> seats) {
        try {
            var r = book(user, key, event, seats).andReturn().getResponse();
            String body = r.getContentAsString();
            String id = r.getStatus() < 300 ? json.readTree(body).get("id").asString() : null;
            return new Response(r.getStatus(), id);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    int activeBookings(long event) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM bookings WHERE event_id=? AND status IN ('HOLDING','PENDING_PAYMENT')",
                Integer.class, event);
    }

    @Test
    void proof1_oneSeat500Users_exactlyOneWinner_andRedisAbsorbsTheCrowd() throws Exception {
        long event = uniqueEvent();
        var responses = burst(500, i -> call(uniqueUser(), "k-" + UUID.randomUUID(), event, List.of(1L)));
        assertThat(responses.stream().filter(r -> r.status() == 201)).hasSize(1);
        assertThat(responses.stream().filter(r -> r.status() == 409)).hasSize(499);
        assertThat(activeBookings(event)).isEqualTo(1);
        assertThat(EVENTS.holdCalls.get()).isEqualTo(1);               // 499 rejected by Redis, never reached event-service
    }

    @Test
    void proof2_redisDown_stillExactlyOneWinner() throws Exception {
        long event = uniqueEvent();
        var docker = REDIS.getDockerClient();
        docker.pauseContainerCmd(REDIS.getContainerId()).exec();
        List<Response> responses;
        try {
            responses = burst(200, i -> call(uniqueUser(), "k-" + UUID.randomUUID(), event, List.of(1L)));
        } finally {
            docker.unpauseContainerCmd(REDIS.getContainerId()).exec();
        }
        assertThat(responses.stream().filter(r -> r.status() == 201)).hasSize(1);
        assertThat(activeBookings(event)).isEqualTo(1);                 // correctness never depended on Redis
        assertThat(EVENTS.holdCalls.get()).isEqualTo(200);              // filter bypassed: everyone reached the source of truth
    }

    @Test
    void proof3_oneUser20ParallelKeys_neverMoreThan4Seats() throws Exception {
        long user = uniqueUser(), event = uniqueEvent();
        burst(20, i -> call(user, "k-" + i + "-" + UUID.randomUUID(), event, List.of(100L + i)));
        Integer active = jdbc.queryForObject("SELECT COALESCE(SUM(seat_count),0) FROM bookings WHERE user_id=? AND event_id=? "
                + "AND status IN ('HOLDING','PENDING_PAYMENT','CONFIRMED')", Integer.class, user, event);
        assertThat(active).isEqualTo(4);
        assertThat(quota(user, event)).isEqualTo(active);
    }

    @Test
    void proof4_sameKey20Times_exactlyOneBooking_andLocksStayWithTheOwner() throws Exception {
        long user = uniqueUser(), event = uniqueEvent();
        String key = "same-" + UUID.randomUUID();
        var responses = burst(20, i -> call(user, key, event, List.of(5L)));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bookings WHERE user_id=?", Integer.class, user)).isEqualTo(1);
        String id = jdbc.queryForObject("SELECT BIN_TO_UUID(id) FROM bookings WHERE user_id=?", String.class, user);
        assertThat(responses).allSatisfy(r -> {
            assertThat(r.status()).isIn(200, 201);
            assertThat(r.bookingId()).isEqualTo(id);
        });
        assertThat(redis.opsForValue().get("bk:seatlock:" + event + ":5")).isEqualTo(user + ":" + key);
        assertThat(quota(user, event)).isEqualTo(1);
        assertThat(EVENTS.holdCalls.get()).isEqualTo(1);
    }
}
