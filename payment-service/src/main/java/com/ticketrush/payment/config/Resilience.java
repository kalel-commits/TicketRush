package com.ticketrush.payment.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.RetryConfig;
import org.springframework.core.env.Environment;

import java.time.Duration;
import java.util.function.Predicate;

/**
 * Spec §14 settings, from {@code ticketrush.resilience.<name>.*} with the spec's defaults. Breakers judge sealed
 * outcome types by {@code recordResult}, because the clients return results rather than throwing.
 */
public final class Resilience {

    private Resilience() {
    }

    public record Settings(float failureRate, int window, int minCalls, Duration open, int maxAttempts, Duration retryWait) {
        /** 50 % failures over 20 calls (at least 10), open 10 s; retry = 1 + 2 attempts, 100 ms apart. */
        public static final Settings DEFAULTS = new Settings(50f, 20, 10, Duration.ofSeconds(10), 3, Duration.ofMillis(100));

        public static Settings from(Environment env, String name) {
            String p = "ticketrush.resilience." + name + ".";
            return new Settings(
                    env.getProperty(p + "failure-rate", Float.class, DEFAULTS.failureRate()),
                    env.getProperty(p + "window", Integer.class, DEFAULTS.window()),
                    env.getProperty(p + "min-calls", Integer.class, DEFAULTS.minCalls()),
                    Duration.ofSeconds(env.getProperty(p + "open-seconds", Long.class, DEFAULTS.open().toSeconds())),
                    env.getProperty(p + "max-attempts", Integer.class, DEFAULTS.maxAttempts()),
                    Duration.ofMillis(env.getProperty(p + "retry-wait-ms", Long.class, DEFAULTS.retryWait().toMillis())));
        }
    }

    public static CircuitBreakerConfig breakerConfig(Settings s, Predicate<Object> isFailure) {
        return CircuitBreakerConfig.custom()
                .failureRateThreshold(s.failureRate())
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(s.window())
                .minimumNumberOfCalls(s.minCalls())
                .waitDurationInOpenState(s.open())
                .permittedNumberOfCallsInHalfOpenState(3)
                .recordResult(isFailure)
                .build();
    }

    /** Retries on matching results only; never on exceptions (CallNotPermittedException means "open": fail fast). */
    public static <T> RetryConfig retryConfig(Settings s, Predicate<T> retryOnResult) {
        return RetryConfig.<T>custom()
                .maxAttempts(s.maxAttempts())
                .waitDuration(s.retryWait())
                .retryOnResult(retryOnResult)
                .retryOnException(e -> false)
                .build();
    }
}
