package com.ticketrush.booking;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InternalApiIT extends IntegrationTestBase {

    @Test
    void getBookingForServices() throws Exception {
        long u = uniqueUser(), e = uniqueEvent();
        String id = bodyOf(book(u, "k-" + UUID.randomUUID(), e, List.of(1L, 2L))).get("id").asString();
        mvc.perform(get("/internal/bookings/" + id).header(AUTHORIZATION, serviceToken())).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.userId").value(u))
                .andExpect(jsonPath("$.eventId").value(e))
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.amountCents").value(5000))
                .andExpect(jsonPath("$.expiresAt").exists());
        mvc.perform(get("/internal/bookings/" + UUID.randomUUID()).header(AUTHORIZATION, serviceToken())).andExpect(status().isNotFound());
        mvc.perform(get("/internal/bookings/" + id).header(AUTHORIZATION, userToken(u))).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/bookings/" + id)).andExpect(status().isUnauthorized());
    }

    @Test
    void batchStatusReturnsKnownIdsOnly() throws Exception {
        long u = uniqueUser(), e = uniqueEvent();
        String id = bodyOf(book(u, "k-" + UUID.randomUUID(), e, List.of(1L))).get("id").asString();
        String body = json.writeValueAsString(Map.of("bookingIds", List.of(id, UUID.randomUUID().toString())));
        mvc.perform(post("/internal/bookings/status").header(AUTHORIZATION, serviceToken())
                        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].bookingId").value(id))
                .andExpect(jsonPath("$[0].status").value("PENDING_PAYMENT"));
        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i < 201; i++) tooMany.add(UUID.randomUUID().toString());
        mvc.perform(post("/internal/bookings/status").header(AUTHORIZATION, serviceToken()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("bookingIds", tooMany)))).andExpect(status().isBadRequest());
    }
}
