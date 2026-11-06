package com.ticketrush.booking.config;

import com.ticketrush.booking.eventclient.EventClient;
import com.ticketrush.booking.eventclient.HoldOutcome;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.retry.Retry;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Spec §14 rows owned by booking-service: "eventHold" (retry + breaker) and "redis" (breaker). */
@Configuration
class ResilienceConfig {

    @Bean
    CircuitBreakerRegistry circuitBreakerRegistry(MeterRegistry meters) {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meters);   // resilience4j_circuitbreaker_state
        return registry;
    }

    @Bean
    CircuitBreaker eventHoldBreaker(CircuitBreakerRegistry registry, Environment env) {
        return registry.circuitBreaker("eventHold",
                Resilience.breakerConfig(Resilience.Settings.from(env, "eventHold"), EventClient::countsAsFailure));
    }

    @Bean
    Retry eventHoldRetry(Environment env) {
        return Retry.of("eventHold",
                Resilience.<HoldOutcome>retryConfig(Resilience.Settings.from(env, "eventHold"), EventClient::isRetryable));
    }

    /** Redis errors are exceptions (the default failure signal), so no result predicate. */
    @Bean
    CircuitBreaker redisBreaker(CircuitBreakerRegistry registry, Environment env) {
        return registry.circuitBreaker("redis", Resilience.breakerConfig(Resilience.Settings.from(env, "redis"), result -> false));
    }
}
