package com.ticketrush.booking;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BookingCreateIT extends IntegrationTestBase {

    @org.springframework.beans.factory.annotation.Autowired
    org.springframework.data.redis.core.StringRedisTemplate redis;

    @Test
    void happyPathHoldsSeatsAndAwaitsPayment() throws Exception {
        long u = uniqueUser(), e = uniqueEvent();
        var r = book(u, "k-" + UUID.randomUUID(), e, List.of(12L, 11L)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.amountCents").value(5000))
                .andExpect(jsonPath("$.seatIds[0]").value(11))
                .andExpect(jsonPath("$.seatNos[0]").value("S11"))
                .andExpect(jsonPath("$.expiresAt").exists());
        String id = bodyOf(r).get("id").asString();
        assertThat(quota(u, e)).isEqualTo(2);
        assertThat(EVENTS.owner(11L)).isEqualTo(id);
        assertThat(EVENTS.authHeaders.getFirst()).startsWith("Bearer ");     // service token sent
        getBooking(u, id).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING_PAYMENT"));
    }

    @Test
    void replayReturnsTheSameBookingAndReusedKeyIs422() throws Exception {
        long u = uniqueUser(), e = uniqueEvent();
        String key = "k-" + UUID.randomUUID();
        String id = bodyOf(book(u, key, e, List.of(1L)).andExpect(status().isCreated())).get("id").asString();
        book(u, key, e, List.of(1L)).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
        book(u, key, e, List.of(2L)).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(EVENTS.holdCalls.get()).isEqualTo(1);
        assertThat(quota(u, e)).isEqualTo(1);
    }

    @Test
    void fifthSeatForTheSameEventIsRejected() throws Exception {
        long u = uniqueUser(), e = uniqueEvent();
        book(u, "a-" + UUID.randomUUID(), e, List.of(1L, 2L, 3L)).andExpect(status().isCreated());
        book(u, "b-" + UUID.randomUUID(), e, List.of(4L)).andExpect(status().isCreated());
        book(u, "c-" + UUID.randomUUID(), e, List.of(5L)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SEAT_LIMIT_EXCEEDED"));
        assertThat(quota(u, e)).isEqualTo(4);
        assertThat(EVENTS.holdCalls.get()).isEqualTo(2);
    }

    @Test
    void definitiveRejectionFailsTheBookingAndReleasesQuota() throws Exception {
        long u = uniqueUser(), e = uniqueEvent();
        EVENTS.mode = FakeEventService.Mode.REJECT_SEATS;
        var r = book(u, "k-" + UUID.randomUUID(), e, List.of(1L)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SEATS_UNAVAILABLE"));
        String id = jdbc.queryForObject("SELECT BIN_TO_UUID(id) FROM bookings WHERE user_id=?", String.class, u);
        assertThat(bookingStatus(id)).isEqualTo("FAILED");
        assertThat(quota(u, e)).isZero();
        assertThat(outboxRows(id, "booking.cancelled")).isZero();     // nothing was held
        EVENTS.mode = FakeEventService.Mode.SALE_NOT_OPEN;
        book(u, "k-" + UUID.randomUUID(), e, List.of(1L)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SALE_NOT_OPEN"));
        EVENTS.mode = FakeEventService.Mode.NOT_FOUND;
        book(u, "k-" + UUID.randomUUID(), e, List.of(1L)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void ambiguousTimeoutFailsReleasesAndSchedulesARelease() throws Exception {
        long u = uniqueUser(), e = uniqueEvent();
        EVENTS.mode = FakeEventService.Mode.TIMEOUT_AFTER_COMMIT;
        book(u, "k-" + UUID.randomUUID(), e, List.of(7L)).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"));
        String id = jdbc.queryForObject("SELECT BIN_TO_UUID(id) FROM bookings WHERE user_id=?", String.class, u);
        assertThat(bookingStatus(id)).isEqualTo("FAILED");
        assertThat(quota(u, e)).isZero();
        assertThat(outboxRows(id, "booking.cancelled")).isEqualTo(1);  // releases whatever event-service committed
        EVENTS.mode = FakeEventService.Mode.FAIL_503;
        book(u, "k-" + UUID.randomUUID(), e, List.of(8L)).andExpect(status().isServiceUnavailable());
    }

    @Test
    void circuitOpenFailsFastWithoutOutbox() throws Exception {
        long u = uniqueUser(), e = uniqueEvent();
        breakers.circuitBreaker("eventHold").transitionToForcedOpenState();
        book(u, "k-" + UUID.randomUUID(), e, List.of(9L)).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"));
        String id = jdbc.queryForObject("SELECT BIN_TO_UUID(id) FROM bookings WHERE user_id=?", String.class, u);
        assertThat(bookingStatus(id)).isEqualTo("FAILED");
        assertThat(quota(u, e)).isZero();
        assertThat(outboxRows(id, "booking.cancelled")).as("nothing was held, nothing to release").isZero();
        assertThat(EVENTS.holdCalls.get()).isZero();
        assertThat(redis.hasKey("bk:seatlock:" + e + ":9")).as("Redis locks released").isFalse();
    }

    @Test
    void badRequestsAndAuth() throws Exception {
        long u = uniqueUser(), e = uniqueEvent();
        book(u, null, e, List.of(1L)).andExpect(status().isBadRequest());
        book(u, "x".repeat(65), e, List.of(1L)).andExpect(status().isBadRequest());
        book(u, "k-" + UUID.randomUUID(), e, List.of(1L, 2L, 3L, 4L, 5L)).andExpect(status().isBadRequest());
        book(u, "k-" + UUID.randomUUID(), e, List.of(1L, 1L)).andExpect(status().isBadRequest());
        String body = "{\"eventId\":1,\"seatIds\":[1]}";
        mvc.perform(post("/api/bookings").header("Idempotency-Key", "k").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/bookings").header("Idempotency-Key", "k").header(AUTHORIZATION, serviceToken())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        assertThat(EVENTS.holdCalls.get()).isZero();
    }

    @Test
    void bookingsAreVisibleOnlyToTheirOwner() throws Exception {
        long alice = uniqueUser(), bob = uniqueUser(), e = uniqueEvent();
        String id = bodyOf(book(alice, "k-" + UUID.randomUUID(), e, List.of(1L))).get("id").asString();
        getBooking(bob, id).andExpect(status().isNotFound());
        getBooking(bob, "not-a-uuid").andExpect(status().isBadRequest());
        mvc.perform(get("/api/bookings/me").header(AUTHORIZATION, userToken(alice))).andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1)).andExpect(jsonPath("$.content[0].id").value(id));
        mvc.perform(get("/api/bookings/me").header(AUTHORIZATION, userToken(bob))).andExpect(jsonPath("$.content.length()").value(0));
    }
}
