package com.ticketrush.payment.provider;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The external PSP (spec §8.2, §10). Never retried here: a retry of an ambiguous charge is exactly what the
 * provider idempotency key (= payment id) and the reconciler (plan 5) exist for.
 */
@Component
public class ProviderClient {

    public sealed interface ChargeOutcome {
        record Approved(String chargeId) implements ChargeOutcome {}

        record Declined() implements ChargeOutcome {}

        /** The request may have reached the provider: money may have moved. */
        record Ambiguous(String reason) implements ChargeOutcome {}

        /** The request never left this service (no connection): no charge happened. */
        record NotSent(String reason) implements ChargeOutcome {}
    }

    public sealed interface RefundOutcome {
        record Refunded(String refundId) implements RefundOutcome {}

        record Failed(String reason) implements RefundOutcome {}
    }

    private final RestClient rest;
    private final ObjectMapper json;
    private final CircuitBreaker breaker;

    ProviderClient(RestClient providerRestClient, ObjectMapper json, @Qualifier("providerBreaker") CircuitBreaker breaker) {
        this.rest = providerRestClient;
        this.json = json;
        this.breaker = breaker;
    }

    /** Failures for the breaker: the provider gave no definitive answer. A decline is a healthy answer. */
    public static boolean countsAsFailure(Object outcome) {
        return outcome instanceof ChargeOutcome.Ambiguous || outcome instanceof ChargeOutcome.NotSent;
    }

    /** Through the "provider" breaker (spec §14). Open: the charge is never sent, so the attempt can safely FAIL. */
    public ChargeOutcome charge(UUID paymentId, long amountCents, UUID bookingId) {
        try {
            return breaker.executeSupplier(() -> sendCharge(paymentId, amountCents, bookingId));
        } catch (CallNotPermittedException open) {
            return new ChargeOutcome.NotSent("CIRCUIT_OPEN");
        }
    }

    private ChargeOutcome sendCharge(UUID paymentId, long amountCents, UUID bookingId) {
        try {
            return rest.post().uri("/charges")
                    .header("Idempotency-Key", paymentId.toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("amountCents", amountCents, "reference", bookingId.toString()))
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status != 200) return new ChargeOutcome.Ambiguous("HTTP " + status);
                        JsonNode n = json.readTree(new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8));
                        return switch (n.get("status").asString()) {
                            case "SUCCEEDED" -> new ChargeOutcome.Approved(n.get("chargeId").asString());
                            case "DECLINED" -> new ChargeOutcome.Declined();
                            default -> new ChargeOutcome.Ambiguous("unknown status " + n.get("status").asString());
                        };
                    });
        } catch (RuntimeException e) {   // RestClientException, and parse errors of a 2xx body: the request was sent
            return neverConnected(e) ? new ChargeOutcome.NotSent(e.getClass().getSimpleName())
                    : new ChargeOutcome.Ambiguous(e.getClass().getSimpleName());
        }
    }

    public RefundOutcome refund(UUID paymentId, String chargeId) {
        try {
            return rest.post().uri("/refunds")
                    .header("Idempotency-Key", "refund-" + paymentId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("chargeId", chargeId))
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status != 200) return new RefundOutcome.Failed("HTTP " + status);
                        JsonNode n = json.readTree(new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8));
                        return new RefundOutcome.Refunded(n.get("refundId").asString());
                    });
        } catch (RuntimeException e) {   // RestClientException, and parse errors of a 2xx body: the request was sent
            return new RefundOutcome.Failed(e.getClass().getSimpleName());
        }
    }

    public sealed interface LookupOutcome {
        record Charged(String chargeId) implements LookupOutcome {}

        record Declined() implements LookupOutcome {}

        record NotFound() implements LookupOutcome {}

        /** No answer: says nothing about whether the charge exists, so it must never count as "not found". */
        record Unavailable(String reason) implements LookupOutcome {}
    }

    public record ProviderCharge(String chargeId, String idempotencyKey, String status, boolean refunded) {}

    /** GET /charges?idempotencyKey=<paymentId>: exact, because the payment id was the charge's idempotency key. */
    public LookupOutcome lookup(UUID paymentId) {
        try {
            return rest.get().uri(b -> b.path("/charges").queryParam("idempotencyKey", paymentId.toString()).build())
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        String body = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
                        if (status == 404) {
                            // only the provider's own "no such charge" counts toward failing a payment (A7); a 404 from a
                            // wrong route, a proxy or an API change must not fail a payment that may have been charged
                            return isChargeNotFound(body) ? new LookupOutcome.NotFound() : new LookupOutcome.Unavailable("HTTP 404");
                        }
                        if (status != 200) return new LookupOutcome.Unavailable("HTTP " + status);
                        JsonNode n = json.readTree(body);
                        return switch (n.get("status").asString()) {
                            case "SUCCEEDED" -> new LookupOutcome.Charged(n.get("chargeId").asString());
                            case "DECLINED" -> new LookupOutcome.Declined();
                            default -> new LookupOutcome.Unavailable("unknown status " + n.get("status").asString());
                        };
                    });
        } catch (RuntimeException e) {
            return new LookupOutcome.Unavailable(e.getClass().getSimpleName());
        }
    }

    private boolean isChargeNotFound(String body) {
        try {
            JsonNode n = json.readTree(body);
            return n.hasNonNull("code") && "CHARGE_NOT_FOUND".equals(n.get("code").asString());
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** GET /charges: every charge the provider knows, for the reconciliation report. */
    public List<ProviderCharge> listCharges() {
        return rest.get().uri("/charges").exchange((request, response) -> {
            if (response.getStatusCode().value() != 200) {
                throw new IllegalStateException("Provider list failed: HTTP " + response.getStatusCode().value());
            }
            JsonNode rows = json.readTree(new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8));
            List<ProviderCharge> out = new ArrayList<>();
            rows.forEach(n -> out.add(new ProviderCharge(n.get("chargeId").asString(), n.get("idempotencyKey").asString(),
                    n.get("status").asString(), n.get("refunded").asBoolean())));
            return out;
        });
    }

    /** Connection refused or connect timeout: the TCP connection never opened, so the request was never sent. */
    static boolean neverConnected(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ConnectException || t instanceof HttpConnectTimeoutException) return true;
        }
        return false;
    }
}
