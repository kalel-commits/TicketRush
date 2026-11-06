package com.ticketrush.booking.seatlock;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Redis fast filter (Approach B, spec §9.1). It only reduces load on the source of truth (event-service's
 * MySQL): a Redis error therefore lets the request through instead of failing it (degrades to Approach A).
 * Calls go through the "redis" breaker (spec §14): while it is open, Redis is not called at all.
 */
@Component
public class SeatLocks {

    private static final Logger log = LoggerFactory.getLogger(SeatLocks.class);

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> acquire = new DefaultRedisScript<>();
    private final DefaultRedisScript<Long> release = new DefaultRedisScript<>();
    private final Counter bypassed;
    private final CircuitBreaker breaker;

    SeatLocks(StringRedisTemplate redis, MeterRegistry meters, @Qualifier("redisBreaker") CircuitBreaker breaker) {
        this.redis = redis;
        this.breaker = breaker;
        acquire.setLocation(new ClassPathResource("redis/acquire.lua"));
        acquire.setResultType(Long.class);
        release.setLocation(new ClassPathResource("redis/release.lua"));
        release.setResultType(Long.class);
        this.bypassed = meters.counter("booking.seatlock.bypassed");
    }

    /** @return false only when another owner holds one of the seats; true on success or when Redis is unavailable */
    public boolean acquire(long eventId, List<Long> seatIds, String owner, Duration ttl) {
        try {
            Long ok = breaker.executeSupplier(() -> redis.execute(acquire, keys(eventId, seatIds), owner, String.valueOf(ttl.toMillis())));
            return ok != null && ok == 1L;
        } catch (CallNotPermittedException open) {
            bypassed.increment();   // breaker open: skip the filter without waiting for Redis
            return true;
        } catch (RuntimeException e) {
            bypassed.increment();
            log.warn("Seat-lock filter unavailable, falling through to the source of truth: {}", e.toString());
            return true;
        }
    }

    /** Best effort; a missed release only delays the seat until the TTL expires. */
    public void release(long eventId, List<Long> seatIds, String owner) {
        try {
            breaker.executeRunnable(() -> redis.execute(release, keys(eventId, seatIds), owner));
        } catch (RuntimeException e) {
            log.warn("Seat-lock release failed (TTL will clean up): {}", e.toString());
        }
    }

    static List<String> keys(long eventId, List<Long> seatIds) {
        return seatIds.stream().sorted().map(s -> "bk:seatlock:" + eventId + ":" + s).toList();
    }
}
