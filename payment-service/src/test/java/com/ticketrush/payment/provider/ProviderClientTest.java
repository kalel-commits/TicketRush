package com.ticketrush.payment.provider;

import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The provider boundary must never throw: anything after the request may have been sent is Ambiguous. */
class ProviderClientTest {

    MockWebServer server;
    ProviderClient client;
    io.github.resilience4j.circuitbreaker.CircuitBreaker breaker;

    @BeforeEach
    void start() throws Exception {
        server = new MockWebServer();
        server.start();
        breaker = io.github.resilience4j.circuitbreaker.CircuitBreaker.ofDefaults("provider");
        client = new ProviderClient(RestClient.builder().baseUrl("http://localhost:" + server.getPort()).build(),
                JsonMapper.builder().build(), breaker);
    }

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void malformed200ChargeBodyIsAmbiguousNotAnException() {
        server.enqueue(new MockResponse.Builder().code(200).body("{not json").build());
        assertThat(client.charge(UUID.randomUUID(), 100, UUID.randomUUID())).isInstanceOf(ProviderClient.ChargeOutcome.Ambiguous.class);
    }

    @Test
    void chargeBodyWithoutStatusIsAmbiguous() {
        server.enqueue(new MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body("{\"chargeId\":\"ch_1\"}").build());
        assertThat(client.charge(UUID.randomUUID(), 100, UUID.randomUUID())).isInstanceOf(ProviderClient.ChargeOutcome.Ambiguous.class);
    }

    @Test
    void malformedRefundBodyIsAFailedRefundNotAnException() {
        server.enqueue(new MockResponse.Builder().code(200).body("{not json").build());
        assertThat(client.refund(UUID.randomUUID(), "ch_1")).isInstanceOf(ProviderClient.RefundOutcome.Failed.class);
    }

    @Test
    void openBreakerMeansTheChargeIsNotSent() {
        breaker.transitionToOpenState();
        assertThat(client.charge(UUID.randomUUID(), 100, UUID.randomUUID())).isEqualTo(new ProviderClient.ChargeOutcome.NotSent("CIRCUIT_OPEN"));
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void breakerCountsAmbiguousAndNotSentAsFailures() {
        assertThat(ProviderClient.countsAsFailure(new ProviderClient.ChargeOutcome.Ambiguous("HTTP 503"))).isTrue();
        assertThat(ProviderClient.countsAsFailure(new ProviderClient.ChargeOutcome.NotSent("ConnectException"))).isTrue();
        assertThat(ProviderClient.countsAsFailure(new ProviderClient.ChargeOutcome.Declined())).isFalse();
        assertThat(ProviderClient.countsAsFailure(new ProviderClient.ChargeOutcome.Approved("ch_1"))).isFalse();
    }

    // ---------- lookup: only the provider's own "no such charge" may count toward failing a payment (A7) ----------

    static MockResponse json(int code, String body) {
        return new MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build();
    }

    @Test
    void lookupClassification() {
        UUID id = UUID.randomUUID();
        server.enqueue(json(200, "{\"chargeId\":\"ch_1\",\"status\":\"SUCCEEDED\"}"));
        server.enqueue(json(200, "{\"chargeId\":\"ch_2\",\"status\":\"DECLINED\"}"));
        server.enqueue(json(404, "{\"code\":\"CHARGE_NOT_FOUND\"}"));
        assertThat(client.lookup(id)).isEqualTo(new ProviderClient.LookupOutcome.Charged("ch_1"));
        assertThat(client.lookup(id)).isEqualTo(new ProviderClient.LookupOutcome.Declined());
        assertThat(client.lookup(id)).isEqualTo(new ProviderClient.LookupOutcome.NotFound());
    }

    @Test
    void anythingElseIsUnavailableNeverNotFound() {
        UUID id = UUID.randomUUID();
        server.enqueue(json(404, "{\"status\":404,\"error\":\"Not Found\"}"));   // wrong route / proxy / API version
        server.enqueue(new MockResponse.Builder().code(404).body("<html>not found</html>").build());
        server.enqueue(json(500, "{}"));
        server.enqueue(json(200, "{\"chargeId\":\"ch_3\",\"status\":\"WEIRD\"}"));
        server.enqueue(json(200, "{not json"));
        for (int i = 0; i < 5; i++) {
            assertThat(client.lookup(id)).as("response %d", i).isInstanceOf(ProviderClient.LookupOutcome.Unavailable.class);
        }
    }

    @Test
    void onlyConnectFailuresCountAsNeverSent() {
        assertThat(ProviderClient.neverConnected(new ResourceAccessException("x", new ConnectException("refused")))).isTrue();
        assertThat(ProviderClient.neverConnected(new ResourceAccessException("x", new HttpConnectTimeoutException("connect timed out")))).isTrue();
        // the request may have been sent: these must stay Ambiguous, or a real charge could free the slot
        assertThat(ProviderClient.neverConnected(new ResourceAccessException("x", new HttpTimeoutException("request timed out")))).isFalse();
        assertThat(ProviderClient.neverConnected(new ResourceAccessException("x", new IOException("Connection reset")))).isFalse();
        assertThat(ProviderClient.neverConnected(new IllegalStateException("parse"))).isFalse();
    }
}
