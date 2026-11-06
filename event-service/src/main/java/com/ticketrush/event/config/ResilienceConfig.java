package com.ticketrush.event.config;

import com.ticketrush.event.reconcile.BookingStatusClient;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Spec §14 row "reconcilers -> booking status": breaker; when open the batch is skipped until the next cycle. */
@Configuration
class ResilienceConfig {

    @Bean
    CircuitBreakerRegistry circuitBreakerRegistry(MeterRegistry meters) {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meters);   // resilience4j_circuitbreaker_state
        return registry;
    }

    @Bean
    CircuitBreaker bookingStatusBreaker(CircuitBreakerRegistry registry, Environment env) {
        return registry.circuitBreaker("bookingStatus",
                Resilience.breakerConfig(Resilience.Settings.from(env, "bookingStatus"), BookingStatusClient::countsAsFailure));
    }
}
