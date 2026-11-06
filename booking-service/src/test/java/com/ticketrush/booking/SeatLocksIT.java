package com.ticketrush.booking;

import com.ticketrush.booking.seatlock.SeatLocks;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Spec §14 row "booking -> Redis": an open breaker skips the filter without touching Redis (degrade to Approach A). */
class SeatLocksIT extends IntegrationTestBase {

    @Autowired
    SeatLocks seatLocks;
    @Autowired
    StringRedisTemplate redis;
    @Autowired
    MeterRegistry meters;

    @Test
    void openRedisBreakerSkipsTheFilterWithoutCallingRedis() {
        long e = uniqueEvent();
        double bypassedBefore = meters.counter("booking.seatlock.bypassed").count();
        breakers.circuitBreaker("redis").transitionToForcedOpenState();
        long start = System.nanoTime();

        boolean acquired = seatLocks.acquire(e, List.of(1L), "u:k", Duration.ofMinutes(1));

        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(50);
        assertThat(acquired).isTrue();
        assertThat(meters.counter("booking.seatlock.bypassed").count()).isEqualTo(bypassedBefore + 1);
        assertThat(redis.hasKey("bk:seatlock:" + e + ":1")).as("Redis was not called").isFalse();
    }

    /** Review finding: the metrics the runbook names must be readable (actuator exposed only "health"). */
    @Test
    void metricsAreReadableFromActuator() throws Exception {
        breakers.circuitBreaker("redis").transitionToForcedOpenState();
        seatLocks.acquire(uniqueEvent(), List.of(1L), "u:k", Duration.ofMinutes(1));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/actuator/metrics/booking.seatlock.bypassed"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/actuator/metrics/resilience4j.circuitbreaker.state"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    }

    @Test
    void closedBreakerStillLocks() {
        long e = uniqueEvent();
        assertThat(seatLocks.acquire(e, List.of(1L), "u:k", Duration.ofMinutes(1))).isTrue();
        assertThat(seatLocks.acquire(e, List.of(1L), "other:k", Duration.ofMinutes(1))).isFalse();
    }
}
