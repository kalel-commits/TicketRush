package com.ticketrush.payment.config;

import com.ticketrush.payment.bookingclient.BookingClient;
import com.ticketrush.payment.provider.ProviderClient;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.retry.Retry;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Spec §14 rows owned by payment-service: "bookingLookup" (retry + breaker), "provider" (breaker, never retried). */
@Configuration
class ResilienceConfig {

    @Bean
    CircuitBreakerRegistry circuitBreakerRegistry(MeterRegistry meters) {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meters);   // resilience4j_circuitbreaker_state
        return registry;
    }

    @Bean
    CircuitBreaker bookingLookupBreaker(CircuitBreakerRegistry registry, Environment env) {
        return registry.circuitBreaker("bookingLookup",
                Resilience.breakerConfig(Resilience.Settings.from(env, "bookingLookup"), BookingClient::countsAsFailure));
    }

    @Bean
    Retry bookingLookupRetry(Environment env) {
        return Retry.of("bookingLookup", Resilience.<BookingClient.BookingLookup>retryConfig(
                Resilience.Settings.from(env, "bookingLookup"), BookingClient::countsAsFailure));
    }

    /** Spec §14 "reconcilers -> booking status": when open, the reconciler skips the batch until the next cycle. */
    @Bean
    CircuitBreaker bookingStatusBreaker(CircuitBreakerRegistry registry, Environment env) {
        return registry.circuitBreaker("bookingStatus",
                Resilience.breakerConfig(Resilience.Settings.from(env, "bookingStatus"), com.ticketrush.payment.reconcile.BookingStatusClient::countsAsFailure));
    }

    @Bean
    CircuitBreaker providerBreaker(CircuitBreakerRegistry registry, Environment env) {
        return registry.circuitBreaker("provider",
                Resilience.breakerConfig(Resilience.Settings.from(env, "provider"), ProviderClient::countsAsFailure));
    }
}
