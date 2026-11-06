package com.ticketrush.event;

import com.ticketrush.common.error.ApiException;
import com.ticketrush.common.error.ErrorCode;
import com.ticketrush.common.id.UuidV7;
import com.ticketrush.event.hold.HoldRequest;
import com.ticketrush.event.hold.HoldService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the MySQL conditional update is the source of truth under real contention (spec §9.2, §16.1 test 1).
 * 500 threads share a 10-connection pool; production's fail-fast 1.5 s pool wait would make slow machines
 * fail on queueing before ever reaching the row lock, so this class waits for connections instead.
 */
@TestPropertySource(properties = "spring.datasource.hikari.connection-timeout=30000")
class ConcurrencyIT extends IntegrationTestBase {

    @Autowired
    HoldService holds;

    @Test
    void singleSeatHasExactlyOneWinner() throws Exception {
        long e = createEventId(1, 1);
        long seat = jdbc.queryForObject("SELECT id FROM seats WHERE event_id=?", Long.class, e);
        AtomicInteger wins = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        ConcurrentLinkedQueue<UUID> winners = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Throwable> unexpected = new ConcurrentLinkedQueue<>();
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 500; i++) {
                pool.submit(() -> {
                    UUID b = UuidV7.next();
                    try {
                        start.await();
                        holds.hold(e, new HoldRequest(b, List.of(seat), 600));
                        wins.incrementAndGet();
                        winners.add(b);
                    } catch (ApiException ex) {
                        if (ex.code() == ErrorCode.SEATS_UNAVAILABLE) conflicts.incrementAndGet(); else unexpected.add(ex);
                    } catch (Throwable t) {
                        unexpected.add(t);
                    }
                });
            }
            start.countDown();
        }
        assertThat(unexpected).isEmpty();
        assertThat(wins.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(499);
        assertThat(jdbc.queryForObject("SELECT BIN_TO_UUID(booking_id) FROM seats WHERE id=?", String.class, seat))
                .isEqualTo(winners.peek().toString());
    }

    @Test
    void overlappingPairsAreAllOrNothing() throws Exception {
        long e = createEventId(1, 10);
        List<Long> seats = jdbc.queryForList("SELECT id FROM seats WHERE event_id=? ORDER BY id", Long.class, e);
        ConcurrentLinkedQueue<Throwable> unexpected = new ConcurrentLinkedQueue<>();
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 200; i++) {
                pool.submit(() -> {
                    List<Long> pick = new ArrayList<>(seats);
                    Collections.shuffle(pick, ThreadLocalRandom.current());
                    try {
                        start.await();
                        holds.hold(e, new HoldRequest(UuidV7.next(), List.of(pick.get(0), pick.get(1)), 600));
                    } catch (ApiException ex) {
                        if (ex.code() != ErrorCode.SEATS_UNAVAILABLE) unexpected.add(ex);
                    } catch (Throwable t) {
                        unexpected.add(t);
                    }
                });
            }
            start.countDown();
        }
        assertThat(unexpected).isEmpty();
        List<Integer> perBooking = jdbc.queryForList(
                "SELECT COUNT(*) FROM seats WHERE event_id=? AND booking_id IS NOT NULL GROUP BY booking_id", Integer.class, e);
        assertThat(perBooking).isNotEmpty().allMatch(n -> n == 2);    // never a partial hold
        Integer held = jdbc.queryForObject("SELECT COUNT(*) FROM seats WHERE event_id=? AND status='HELD'", Integer.class, e);
        assertThat(held).isEqualTo(perBooking.size() * 2);              // no seat counted twice
    }
}
