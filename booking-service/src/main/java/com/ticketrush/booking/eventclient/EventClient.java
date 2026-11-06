package com.ticketrush.booking.eventclient;

import com.ticketrush.common.security.ServiceTokens;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Calls event-service's internal hold endpoint with this service's own service token, through
 * Retry(CircuitBreaker) (spec §14): up to 2 retries on "not sent" or 5xx, never on a read timeout.
 */
@Component
public class EventClient {

    private final RestClient rest;
    private final ServiceTokens.Provider tokens;
    private final ObjectMapper json;
    private final CircuitBreaker breaker;
    private final Retry retry;

    EventClient(RestClient eventServiceRestClient, ServiceTokens.Provider tokens, ObjectMapper json,
                @Qualifier("eventHoldBreaker") CircuitBreaker breaker, @Qualifier("eventHoldRetry") Retry retry) {
        this.rest = eventServiceRestClient;
        this.tokens = tokens;
        this.json = json;
        this.breaker = breaker;
        this.retry = retry;
    }

    /** Failures for the breaker: anything that is neither a hold nor a definitive answer. */
    public static boolean countsAsFailure(Object outcome) {
        return outcome instanceof HoldOutcome.Ambiguous || outcome instanceof HoldOutcome.NotSent;
    }

    public static boolean isRetryable(HoldOutcome outcome) {
        return outcome instanceof HoldOutcome.NotSent
                || outcome instanceof HoldOutcome.Ambiguous a && a.retryable();
    }

    public HoldOutcome hold(long eventId, UUID bookingId, List<Long> seatIds, int holdSeconds) {
        AtomicBoolean sent = new AtomicBoolean();
        Supplier<HoldOutcome> attempt = CircuitBreaker.decorateSupplier(breaker, () -> {
            HoldOutcome o = call(eventId, bookingId, seatIds, holdSeconds);
            if (!(o instanceof HoldOutcome.NotSent)) sent.set(true);
            return o;
        });
        try {
            HoldOutcome outcome = Retry.decorateSupplier(retry, attempt).get();
            // a later attempt that never connected says nothing about an earlier one (e.g. a 5xx) that may have committed
            return sent.get() && outcome instanceof HoldOutcome.NotSent
                    ? new HoldOutcome.Ambiguous("NOT_SENT_AFTER_ATTEMPT", false) : outcome;
        } catch (CallNotPermittedException open) {
            // once an attempt reached event-service its hold may have committed: keep it ambiguous so it is released
            return sent.get() ? new HoldOutcome.Ambiguous("CIRCUIT_OPEN_AFTER_ATTEMPT", false) : new HoldOutcome.NotSent("CIRCUIT_OPEN");
        }
    }

    private HoldOutcome call(long eventId, UUID bookingId, List<Long> seatIds, int holdSeconds) {
        try {
            return rest.post().uri("/internal/events/{eventId}/holds", eventId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.token())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("bookingId", bookingId.toString(), "seatIds", seatIds, "holdSeconds", holdSeconds))
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        String body = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
                        if (status == 200) {
                            JsonNode n = json.readTree(body);
                            List<String> seatNos = new ArrayList<>();
                            n.get("seatNos").forEach(s -> seatNos.add(s.asString()));
                            return new HoldOutcome.Held(Instant.parse(n.get("heldUntil").asString()), n.get("amountCents").asLong(), seatNos);
                        }
                        if (status >= 500) {
                            return new HoldOutcome.Ambiguous("HTTP " + status, true);
                        }
                        return new HoldOutcome.Rejected(status, code(body));
                    });
        } catch (RuntimeException e) {   // RestClientException, and IllegalStateException when the load balancer has no instance yet
            return neverConnected(e) ? new HoldOutcome.NotSent(e.getClass().getSimpleName())
                    : new HoldOutcome.Ambiguous(e.getClass().getSimpleName(), false);   // read timeout, no instance, bad body
        }
    }

    /** Connection refused or connect timeout: the TCP connection never opened, so the request was never sent. */
    static boolean neverConnected(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ConnectException || t instanceof HttpConnectTimeoutException) return true;
        }
        return false;
    }

    private String code(String body) {
        try {
            JsonNode n = json.readTree(body);
            return n.hasNonNull("code") ? n.get("code").asString() : "UNKNOWN";
        } catch (RuntimeException e) {
            return "UNKNOWN";
        }
    }
}
