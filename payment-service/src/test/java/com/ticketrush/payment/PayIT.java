package com.ticketrush.payment;

import com.ticketrush.common.id.UuidV7;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** POST /api/payments (spec §8.2) against a fake booking-service and a fake provider. */
class PayIT extends IntegrationTestBase {

    @Test
    void happyPathChargesOnceAndWritesTheOutboxEvent() throws Exception {
        long u = uniqueUser();
        UUID b = pendingBooking(u);
        JsonNode body = bodyOf(pay(u, "k1", b).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.bookingId").value(b.toString()))
                .andExpect(jsonPath("$.amountCents").value(PRICE)));
        String id = body.get("id").asString();
        assertThat(paymentStatus(id)).isEqualTo("SUCCEEDED");
        var charge = PROVIDER.chargeFor(UUID.fromString(id));
        assertThat(charge).as("provider idempotency key = payment id").isNotNull();
        assertThat(charge.amountCents()).isEqualTo(PRICE);
        assertThat(jdbc.queryForObject("SELECT provider_ref FROM payments WHERE id=UUID_TO_BIN(?)", String.class, id))
                .isEqualTo(charge.chargeId());
        assertThat(outboxRows(b, "payment.succeeded")).isEqualTo(1);
        String payload = jdbc.queryForObject("SELECT payload FROM outbox_events WHERE aggregate_id=UUID_TO_BIN(?)", String.class, b.toString());
        JsonNode p = json.readTree(payload).get("payload");
        assertThat(p.get("paymentId").asString()).isEqualTo(id);
        assertThat(p.get("bookingId").asString()).isEqualTo(b.toString());
        assertThat(p.get("amountCents").asLong()).isEqualTo(PRICE);
        assertThat(BOOKINGS.authHeaders).allMatch(h -> h.startsWith("Bearer "));
    }

    @Test
    void replayReturnsTheSameAttemptWithoutChargingAgain() throws Exception {
        long u = uniqueUser();
        UUID b = pendingBooking(u);
        String id = bodyOf(pay(u, "k1", b).andExpect(status().isOk())).get("id").asString();
        pay(u, "k1", b).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
        assertThat(PROVIDER.chargeCalls.get()).isEqualTo(1);
        pay(u, "k1", pendingBooking(u)).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void declineFreesTheSlotForANewAttempt() throws Exception {
        long u = uniqueUser();
        UUID b = pendingBooking(u);
        PROVIDER.mode = FakeProvider.Mode.DECLINE;
        String failedId = bodyOf(pay(u, "k1", b).andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.code").value("DECLINED"))).get("paymentId").asString();
        assertThat(paymentStatus(failedId)).isEqualTo("FAILED");
        assertThat(failureReason(failedId)).isEqualTo("DECLINED");
        pay(u, "k1", b).andExpect(status().isPaymentRequired()).andExpect(jsonPath("$.paymentId").value(failedId));   // replay

        PROVIDER.mode = FakeProvider.Mode.APPROVE;
        pay(u, "k2", b).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCEEDED"));
        assertThat(outboxRows(b, "payment.succeeded")).isEqualTo(1);
    }

    @Test
    void ambiguousTimeoutStaysProcessingAndBlocksTheSlot() throws Exception {
        long u = uniqueUser();
        UUID b = pendingBooking(u);
        PROVIDER.mode = FakeProvider.Mode.TIMEOUT_AFTER_CHARGE;
        long start = System.nanoTime();
        String id = bodyOf(pay(u, "k1", b).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PROCESSING"))).get("id").asString();
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(3_900);
        assertThat(paymentStatus(id)).isEqualTo("PROCESSING");
        assertThat(PROVIDER.charges()).as("the provider did take the money").isEqualTo(1);
        assertThat(outboxRows(b, "payment.succeeded")).isZero();

        pay(u, "k1", b).andExpect(status().isAccepted()).andExpect(jsonPath("$.id").value(id));
        assertThat(PROVIDER.chargeCalls.get()).isEqualTo(1);
        pay(u, "k2", b).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_IN_PROGRESS_OR_DONE"))
                .andExpect(jsonPath("$.paymentId").value(id));
    }

    @Test
    void provider5xxIsAmbiguous() throws Exception {
        long u = uniqueUser();
        UUID b = pendingBooking(u);
        PROVIDER.mode = FakeProvider.Mode.ERROR_500;
        String id = bodyOf(pay(u, "k1", b).andExpect(status().isAccepted())).get("id").asString();
        assertThat(paymentStatus(id)).isEqualTo("PROCESSING");
    }

    @Test
    void connectionRefusedMeansNoChargeSoTheSlotIsFreed() throws Exception {
        long u = uniqueUser();
        UUID b = pendingBooking(u);
        PROVIDER.refuseConnections();
        String first = bodyOf(pay(u, "k1", b).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"))).get("paymentId").asString();
        assertThat(paymentStatus(first)).isEqualTo("FAILED");
        assertThat(failureReason(first)).isEqualTo("PROVIDER_UNAVAILABLE");
        pay(u, "k2", b).andExpect(status().isServiceUnavailable());   // 503, not 409: the slot was free
        assertThat(paymentRows(b)).isEqualTo(2);
        assertThat(activeRows(b)).isZero();
    }

    @Test
    void providerCircuitOpenFailsTheAttempt() throws Exception {
        long u = uniqueUser();
        UUID b = pendingBooking(u);
        breakers.circuitBreaker("provider").transitionToForcedOpenState();
        String id = bodyOf(pay(u, "k1", b).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"))).get("paymentId").asString();
        assertThat(paymentStatus(id)).isEqualTo("FAILED");
        assertThat(failureReason(id)).isEqualTo("PROVIDER_UNAVAILABLE");
        assertThat(PROVIDER.chargeCalls.get()).isZero();
    }

    @Test
    void bookingMustBePayableAndOwned() throws Exception {
        long u = uniqueUser();
        pay(u, "k1", booking(u, "CONFIRMED", Instant.now().plus(5, ChronoUnit.MINUTES)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATE"));
        pay(u, "k2", booking(u, "PENDING_PAYMENT", Instant.now().minus(1, ChronoUnit.HOURS)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATE"));
        pay(u, "k3", pendingBooking(uniqueUser())).andExpect(status().isNotFound());
        pay(u, "k4", UUID.randomUUID()).andExpect(status().isNotFound());
        BOOKINGS.failing = true;
        UUID b = UUID.randomUUID();
        pay(u, "k5", b).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"));
        assertThat(paymentRows(b)).isZero();
        assertThat(PROVIDER.chargeCalls.get()).isZero();
    }

    @Test
    void requestValidationAndAuth() throws Exception {
        long u = uniqueUser();
        UUID b = pendingBooking(u);
        pay(u, null, b).andExpect(status().isBadRequest());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/payments")
                .header("Idempotency-Key", "k").contentType("application/json").content("{\"bookingId\":\"" + b + "\"}"))
                .andExpect(status().isUnauthorized());
        String id = bodyOf(pay(u, "k1", b)).get("id").asString();
        mvc.perform(get("/api/payments/" + id).header(AUTHORIZATION, userToken(u)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCEEDED"));
        mvc.perform(get("/api/payments/" + id).header(AUTHORIZATION, userToken(uniqueUser()))).andExpect(status().isNotFound());
    }

    @Test
    void activeBookingIndexAllowsManyFailedButOneActive() {
        UUID booking = UUID.randomUUID();
        insert(booking, "FAILED");
        insert(booking, "FAILED");
        insert(booking, "PROCESSING");
        assertThatThrownBy(() -> insert(booking, "PROCESSING")).isInstanceOf(DuplicateKeyException.class);
        assertThatThrownBy(() -> insert(booking, "SUCCEEDED")).isInstanceOf(DuplicateKeyException.class);
    }

    private void insert(UUID booking, String status) {
        jdbc.update("INSERT INTO payments (id, booking_id, user_id, amount_cents, status, idempotency_key, request_hash, created_at, updated_at) "
                        + "VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), 1, 100, ?, ?, REPEAT('a',64), NOW(3), NOW(3))",
                UuidV7.next().toString(), booking.toString(), status, UUID.randomUUID().toString());
    }
}
