package com.ticketrush.payment.bookingclient;

import com.ticketrush.common.security.ServiceTokens;
import com.ticketrush.payment.config.Resilience;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Spec §14 row "payment -> booking get": retry x2, breaker, 503 when open. */
class BookingClientTest {

    static final Resilience.Settings FAST = new Resilience.Settings(50f, 20, 10, Duration.ofSeconds(10), 3, Duration.ofMillis(10));
    static final ServiceTokens.Provider TOKENS = ServiceTokens.provider("unit-test-internal-secret-0123456789", "payment-service", Clock.systemUTC());

    MockWebServer server;
    CircuitBreaker breaker;

    @BeforeEach
    void start() throws Exception {
        server = new MockWebServer();
        server.start();
        breaker = CircuitBreaker.of("bookingLookup", Resilience.breakerConfig(FAST, BookingClient::countsAsFailure));
    }

    @AfterEach
    void stop() {
        server.close();
    }

    BookingClient client(RestClient rest) {
        return new BookingClient(rest, TOKENS, JsonMapper.builder().build(), breaker,
                Retry.of("bookingLookup", Resilience.<BookingClient.BookingLookup>retryConfig(FAST, BookingClient::countsAsFailure)));
    }

    /** Same request factory as production (JDK HttpClient, 1 s read timeout). */
    BookingClient client() {
        var factory = new org.springframework.http.client.JdkClientHttpRequestFactory(
                java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).build());
        factory.setReadTimeout(Duration.ofSeconds(1));
        return client(RestClient.builder().baseUrl("http://localhost:" + server.getPort()).requestFactory(factory).build());
    }

    static MockResponse status(int code, String body) {
        return new MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build();
    }

    @Test
    void noLoadBalancerInstanceIsUnavailable() {
        RestClient noInstances = RestClient.builder().baseUrl("http://booking-service")
                .requestInterceptor((request, body, execution) -> {
                    throw new IllegalStateException("No instances available for booking-service");
                }).build();
        assertThat(client(noInstances).get(UUID.randomUUID())).isInstanceOf(BookingClient.BookingLookup.Unavailable.class);
    }

    @Test
    void retriesTwiceThenUnavailable() {
        for (int i = 0; i < 3; i++) server.enqueue(status(503, "{}"));
        assertThat(client().get(UUID.randomUUID())).isInstanceOf(BookingClient.BookingLookup.Unavailable.class);
        assertThat(server.getRequestCount()).isEqualTo(3);
    }

    @Test
    void retrySucceedsOnTheThirdAttempt() {
        UUID id = UUID.randomUUID();
        server.enqueue(status(503, "{}"));
        server.enqueue(status(503, "{}"));
        server.enqueue(status(200, """
                {"id":"%s","userId":5,"eventId":7,"status":"PENDING_PAYMENT","amountCents":2500,"expiresAt":"2026-11-02T10:00:00Z","paymentId":null}"""
                .formatted(id)));
        assertThat(client().get(id)).isInstanceOf(BookingClient.BookingLookup.Found.class);
    }

    @Test
    void openBreakerIsUnavailableWithoutACall() {
        breaker.transitionToOpenState();
        assertThat(client().get(UUID.randomUUID())).isEqualTo(new BookingClient.BookingLookup.Unavailable("CIRCUIT_OPEN"));
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void notFoundIsNeitherRetriedNorAFailure() {
        server.enqueue(status(404, "{}"));
        assertThat(client().get(UUID.randomUUID())).isInstanceOf(BookingClient.BookingLookup.NotFound.class);
        assertThat(server.getRequestCount()).isEqualTo(1);
    }
}
