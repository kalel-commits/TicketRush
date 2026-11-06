package com.ticketrush.event.reconcile;

import com.ticketrush.common.security.ServiceTokens;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** booking-service's POST /internal/bookings/status (plan 3 contract): the owner's current truth for a batch. */
@Component
public class BookingStatusClient {

    public sealed interface StatusLookup {
        /** bookingId -> status; ids booking-service does not know are absent. */
        record Found(Map<UUID, String> statuses) implements StatusLookup {}

        record Unavailable(String reason) implements StatusLookup {}
    }

    private final RestClient rest;
    private final ServiceTokens.Provider tokens;
    private final ObjectMapper json;
    private final CircuitBreaker breaker;

    BookingStatusClient(RestClient bookingRestClient, ServiceTokens.Provider tokens, ObjectMapper json,
                        @Qualifier("bookingStatusBreaker") CircuitBreaker breaker) {
        this.rest = bookingRestClient;
        this.tokens = tokens;
        this.json = json;
        this.breaker = breaker;
    }

    public static boolean countsAsFailure(Object lookup) {
        return lookup instanceof StatusLookup.Unavailable;
    }

    public StatusLookup statuses(List<UUID> bookingIds) {
        try {
            return breaker.executeSupplier(() -> call(bookingIds));
        } catch (CallNotPermittedException open) {
            return new StatusLookup.Unavailable("CIRCUIT_OPEN");
        }
    }

    private StatusLookup call(List<UUID> bookingIds) {
        try {
            return rest.post().uri("/internal/bookings/status")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.token())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("bookingIds", bookingIds.stream().map(UUID::toString).toList()))
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status != 200) return new StatusLookup.Unavailable("HTTP " + status);
                        JsonNode rows = json.readTree(new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8));
                        Map<UUID, String> out = new HashMap<>();
                        rows.forEach(r -> out.put(UUID.fromString(r.get("bookingId").asString()), r.get("status").asString()));
                        return new StatusLookup.Found(out);
                    });
        } catch (RuntimeException e) {   // timeouts, connection errors, no instance yet, malformed body
            return new StatusLookup.Unavailable(e.getClass().getSimpleName());
        }
    }
}
