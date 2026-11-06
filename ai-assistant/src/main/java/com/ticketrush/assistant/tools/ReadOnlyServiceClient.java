package com.ticketrush.assistant.tools;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

/**
 * The assistant's only way to reach business services (spec §24.5): GET only, only four path templates, only
 * event-service and booking-service, always with the caller's own token. Anything else fails before any I/O, and
 * this class has no method for any other HTTP verb.
 */
@Component
public class ReadOnlyServiceClient {

    /** not_found (404/403: absent or not the caller's) or unavailable (timeout, 5xx, open breaker). */
    public static final class LookupFailed extends RuntimeException {
        private final String reason;

        LookupFailed(String reason) {
            super(reason, null, false, false);
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
    }

    private static final Map<String, Set<String>> ALLOWED = Map.of(
            "event-service", Set.of("/api/events", "/api/events/{id}"),
            "booking-service", Set.of("/api/bookings/me", "/api/bookings/{id}"));

    private final RestClient rest;
    private final ObjectMapper json;
    private final CircuitBreakerRegistry breakers;

    ReadOnlyServiceClient(@Qualifier("toolsRestClient") RestClient rest, ObjectMapper json, CircuitBreakerRegistry breakers) {
        this.rest = rest;
        this.json = json;
        this.breakers = breakers;
    }

    public JsonNode get(String serviceId, String pathTemplate, Map<String, ?> vars, String bearerToken) {
        if (!ALLOWED.getOrDefault(serviceId, Set.of()).contains(pathTemplate)) {
            throw new IllegalArgumentException("Not an allowed read-only call: " + serviceId + " " + pathTemplate);
        }
        // "not found / not yours" is a healthy answer: only unavailability counts toward opening the breaker
        CircuitBreaker breaker = breakers.circuitBreaker("tools-" + serviceId, CircuitBreakerConfig.custom()
                .ignoreException(e -> e instanceof LookupFailed f && "not_found".equals(f.reason())).build());
        try {
            return breaker.executeSupplier(() -> {
                try {
                    return once(serviceId, pathTemplate, vars, bearerToken);
                } catch (LookupFailed e) {
                    if (!"unavailable".equals(e.reason())) throw e;
                    return once(serviceId, pathTemplate, vars, bearerToken);   // one retry: GETs are safe to repeat
                }
            });
        } catch (CallNotPermittedException open) {
            throw new LookupFailed("unavailable");
        }
    }

    private JsonNode once(String serviceId, String pathTemplate, Map<String, ?> vars, String bearerToken) {
        try {
            return rest.get().uri("http://" + serviceId + pathTemplate, vars)
                    .header(HttpHeaders.AUTHORIZATION, bearerToken)
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 404 || status == 403) throw new LookupFailed("not_found");
                        if (status != 200) throw new LookupFailed("unavailable");
                        return json.readTree(new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8));
                    });
        } catch (LookupFailed e) {
            throw e;
        } catch (RuntimeException e) {   // timeouts, connection errors, no instance yet, malformed body
            throw new LookupFailed("unavailable");
        }
    }
}
