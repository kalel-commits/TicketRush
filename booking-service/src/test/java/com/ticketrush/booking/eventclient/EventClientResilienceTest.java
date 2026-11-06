package com.ticketrush.booking.eventclient;

import com.ticketrush.booking.config.Resilience;
import com.ticketrush.common.security.ServiceTokens;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Spec §14 row "booking -> event hold": retry x2 on connect error/5xx, breaker 50 %, fail fast when open. */
class EventClientResilienceTest {

    static final Resilience.Settings SMALL = new Resilience.Settings(50f, 4, 2, Duration.ofSeconds(10), 3, Duration.ofMillis(10));
    static final String HELD = "{\"heldUntil\":\"2026-11-02T10:00:00Z\",\"amountCents\":2500,\"seatNos\":[\"A1\"]}";

    MockWebServer server;
    CircuitBreaker breaker;
    EventClient client;

    @BeforeEach
    void start() throws Exception {
        server = new MockWebServer();
        server.start();
        breaker = CircuitBreaker.of("eventHold", Resilience.breakerConfig(SMALL, EventClient::countsAsFailure));
        Retry retry = Retry.of("eventHold", Resilience.<HoldOutcome>retryConfig(SMALL, EventClient::isRetryable));
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).build());
        factory.setReadTimeout(Duration.ofMillis(300));
        client = new EventClient(RestClient.builder().baseUrl("http://localhost:" + server.getPort()).requestFactory(factory).build(),
                ServiceTokens.provider("unit-test-internal-secret-0123456789", "booking-service", Clock.systemUTC()),
                JsonMapper.builder().build(), breaker, retry);
    }

    @AfterEach
    void stop() {
        server.close();
    }

    static MockResponse status(int code, String body) {
        return new MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build();
    }

    HoldOutcome hold() {
        return client.hold(1L, UUID.randomUUID(), List.of(1L), 600);
    }

    @Test
    void retriesTwiceOn5xxThenSucceeds() {
        // a breaker that cannot open within 3 calls, so this test sees the retries alone
        var lenient = new Resilience.Settings(50f, 20, 10, Duration.ofSeconds(10), 3, Duration.ofMillis(10));
        client = new EventClient(RestClient.builder().baseUrl("http://localhost:" + server.getPort()).build(),
                ServiceTokens.provider("unit-test-internal-secret-0123456789", "booking-service", Clock.systemUTC()),
                JsonMapper.builder().build(),
                CircuitBreaker.of("eventHold", Resilience.breakerConfig(lenient, EventClient::countsAsFailure)),
                Retry.of("eventHold", Resilience.<HoldOutcome>retryConfig(lenient, EventClient::isRetryable)));
        server.enqueue(status(503, "{}"));
        server.enqueue(status(503, "{}"));
        server.enqueue(status(200, HELD));

        assertThat(hold()).isInstanceOf(HoldOutcome.Held.class);
        assertThat(server.getRequestCount()).isEqualTo(3);
    }

    @Test
    void readTimeoutIsNotRetriedBecauseTheHoldMayHaveCommitted() {
        server.enqueue(new MockResponse.Builder().code(200).headersDelay(1, TimeUnit.SECONDS).body(HELD).build());

        HoldOutcome outcome = hold();

        assertThat(outcome).isInstanceOf(HoldOutcome.Ambiguous.class);
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void breakerOpeningMidRetryAfterASentAttemptStaysAmbiguous() {
        server.enqueue(status(503, "{}"));
        server.enqueue(status(503, "{}"));   // breaker opens after this one (2 calls, 100 % failures)

        assertThat(hold()).as("an attempt reached event-service, so the release must still be published")
                .isInstanceOf(HoldOutcome.Ambiguous.class);
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    /** Review finding: 503 then "connection refused" must stay Ambiguous; the 503 attempt may have committed. */
    @Test
    void notSentAfterAnAttemptThatReachedEventServiceStaysAmbiguous() {
        var lenient = new Resilience.Settings(50f, 20, 10, Duration.ofSeconds(10), 3, Duration.ofMillis(10));
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        RestClient rest = RestClient.builder().baseUrl("http://localhost:" + server.getPort())
                .requestFactory(new JdkClientHttpRequestFactory(HttpClient.newHttpClient()))   // production's client
                .requestInterceptor((request, body, execution) -> {
                    if (calls.incrementAndGet() > 1) throw new java.net.ConnectException("Connection refused");
                    return execution.execute(request, body);
                }).build();
        client = new EventClient(rest, ServiceTokens.provider("unit-test-internal-secret-0123456789", "booking-service", Clock.systemUTC()),
                JsonMapper.builder().build(),
                CircuitBreaker.of("eventHold", Resilience.breakerConfig(lenient, EventClient::countsAsFailure)),
                Retry.of("eventHold", Resilience.<HoldOutcome>retryConfig(lenient, EventClient::isRetryable)));
        server.enqueue(status(503, "{}"));

        HoldOutcome outcome = hold();

        assertThat(calls.get()).as("1 sent + 2 refused retries").isEqualTo(3);
        assertThat(outcome).isInstanceOf(HoldOutcome.Ambiguous.class);
    }

    @Test
    void openBreakerFailsFastWithoutCallingEventService() {
        breaker.transitionToOpenState();
        long start = System.nanoTime();

        HoldOutcome outcome = hold();

        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(50);
        assertThat(outcome).isEqualTo(new HoldOutcome.NotSent("CIRCUIT_OPEN"));
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void retryAndFailureClassification() {
        assertThat(EventClient.isRetryable(new HoldOutcome.NotSent("ConnectException"))).isTrue();
        assertThat(EventClient.isRetryable(new HoldOutcome.Ambiguous("HTTP 503", true))).isTrue();
        assertThat(EventClient.isRetryable(new HoldOutcome.Ambiguous("timeout", false))).isFalse();
        assertThat(EventClient.isRetryable(new HoldOutcome.Rejected(409, "SEATS_UNAVAILABLE"))).isFalse();
        assertThat(EventClient.countsAsFailure(new HoldOutcome.Rejected(409, "SEATS_UNAVAILABLE"))).isFalse();
        assertThat(EventClient.countsAsFailure(new HoldOutcome.Ambiguous("timeout", false))).isTrue();
    }
}
