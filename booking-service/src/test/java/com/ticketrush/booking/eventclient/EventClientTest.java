package com.ticketrush.booking.eventclient;

import com.ticketrush.common.security.ServiceTokens;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression: right after startup the load balancer has no event-service instance yet and throws
 * IllegalStateException (not a RestClientException). That escaped as a 500 and left the booking HOLDING.
 */
class EventClientTest {

    @Test
    void noLoadBalancerInstanceIsAnAmbiguousOutcomeNotAnException() {
        RestClient noInstances = RestClient.builder().baseUrl("http://event-service")
                .requestInterceptor((request, body, execution) -> {
                    throw new IllegalStateException("No instances available for event-service");
                }).build();
        var client = new EventClient(noInstances,
                ServiceTokens.provider("unit-test-internal-secret-0123456789", "booking-service", Clock.systemUTC()),
                JsonMapper.builder().build(),
                io.github.resilience4j.circuitbreaker.CircuitBreaker.ofDefaults("eventHold"),
                io.github.resilience4j.retry.Retry.of("eventHold", com.ticketrush.booking.config.Resilience.<HoldOutcome>retryConfig(
                        com.ticketrush.booking.config.Resilience.Settings.DEFAULTS, EventClient::isRetryable)));

        HoldOutcome outcome = client.hold(1L, UUID.randomUUID(), List.of(1L), 600);

        assertThat(outcome).isInstanceOf(HoldOutcome.Ambiguous.class);
    }
}
