package com.ticketrush.booking;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CancelIT extends IntegrationTestBase {

    @Autowired
    StringRedisTemplate redis;

    @Test
    void ownerCancelsPendingBookingOnce() throws Exception {
        long u = uniqueUser(), e = uniqueEvent();
        String id = bodyOf(book(u, "k-" + UUID.randomUUID(), e, List.of(3L, 4L))).get("id").asString();
        mvc.perform(post("/api/bookings/" + id + "/cancel").header(AUTHORIZATION, userToken(u)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(quota(u, e)).isZero();
        assertThat(outboxRows(id, "booking.cancelled")).isEqualTo(1);
        assertThat(redis.hasKey("bk:seatlock:" + e + ":3")).isFalse();
        mvc.perform(post("/api/bookings/" + id + "/cancel").header(AUTHORIZATION, userToken(u)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATE"));
        assertThat(outboxRows(id, "booking.cancelled")).isEqualTo(1);
    }

    @Test
    void cannotCancelSomeoneElsesBooking() throws Exception {
        long alice = uniqueUser(), bob = uniqueUser(), e = uniqueEvent();
        String id = bodyOf(book(alice, "k-" + UUID.randomUUID(), e, List.of(1L))).get("id").asString();
        mvc.perform(post("/api/bookings/" + id + "/cancel").header(AUTHORIZATION, userToken(bob))).andExpect(status().isNotFound());
        assertThat(bookingStatus(id)).isEqualTo("PENDING_PAYMENT");
    }
}
