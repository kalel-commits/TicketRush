package com.ticketrush.payment.bookingclient;

import com.ticketrush.common.security.ServiceTokens;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/** GET /internal/bookings/{id} with a service token (plan 3 contract), through Retry(CircuitBreaker) (spec §14). */
@Component
public class BookingClient {

    public sealed interface BookingLookup {
        record Found(UUID id, long userId, String status, long amountCents, Instant expiresAt) implements BookingLookup {}

        record NotFound() implements BookingLookup {}

        record Unavailable(String reason) implements BookingLookup {}
    }

    private final RestClient rest;
    private final ServiceTokens.Provider tokens;
    private final ObjectMapper json;
    private final CircuitBreaker breaker;
    private final Retry retry;

    BookingClient(RestClient bookingRestClient, ServiceTokens.Provider tokens, ObjectMapper json,
                  @Qualifier("bookingLookupBreaker") CircuitBreaker breaker, @Qualifier("bookingLookupRetry") Retry retry) {
        this.rest = bookingRestClient;
        this.tokens = tokens;
        this.json = json;
        this.breaker = breaker;
        this.retry = retry;
    }

    /** Failure for the breaker, and the retry trigger: booking-service did not give a usable answer. */
    public static boolean countsAsFailure(Object lookup) {
        return lookup instanceof BookingLookup.Unavailable;
    }

    public BookingLookup get(UUID bookingId) {
        try {
            return Retry.decorateSupplier(retry, CircuitBreaker.decorateSupplier(breaker, () -> call(bookingId))).get();
        } catch (CallNotPermittedException open) {
            return new BookingLookup.Unavailable("CIRCUIT_OPEN");
        }
    }

    private BookingLookup call(UUID bookingId) {
        try {
            return rest.get().uri("/internal/bookings/{id}", bookingId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.token())
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 404) return new BookingLookup.NotFound();
                        if (status != 200) return new BookingLookup.Unavailable("HTTP " + status);
                        JsonNode n = json.readTree(new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8));
                        return new BookingLookup.Found(UUID.fromString(n.get("id").asString()), n.get("userId").asLong(),
                                n.get("status").asString(), n.get("amountCents").asLong(), Instant.parse(n.get("expiresAt").asString()));
                    });
        } catch (RuntimeException e) {   // RestClientException, and IllegalStateException when the load balancer has no instance yet
            return new BookingLookup.Unavailable(e.getClass().getSimpleName());   // timeouts, connection refused, no instance
        }
    }
}
