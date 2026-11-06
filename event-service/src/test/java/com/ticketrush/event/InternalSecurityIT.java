package com.ticketrush.event;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InternalSecurityIT extends IntegrationTestBase {

    private static final String HOLD = "{\"bookingId\":\"0192f0c4-0000-7000-8000-000000000001\",\"seatIds\":[1],\"holdSeconds\":600}";

    @Test
    void internalAcceptsOnlyServiceTokens() throws Exception {
        mvc.perform(post("/internal/events/999999/holds").contentType(MediaType.APPLICATION_JSON).content(HOLD))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/events/999999/holds").header(AUTHORIZATION, admin())
                .contentType(MediaType.APPLICATION_JSON).content(HOLD)).andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/events/999999/holds").header(AUTHORIZATION, service())
                .contentType(MediaType.APPLICATION_JSON).content(HOLD)).andExpect(status().isNotFound());   // passes security, unknown event
    }

    @Test
    void unmappedInternalPathWithServiceTokenIs404() throws Exception {
        mvc.perform(get("/internal/anything").header(AUTHORIZATION, service())).andExpect(status().isNotFound());
    }

    @Test
    void unknownPublicPathsAreDenied() throws Exception {
        mvc.perform(get("/foo")).andExpect(status().isUnauthorized());
        mvc.perform(get("/foo").header(AUTHORIZATION, user())).andExpect(status().isForbidden());
    }
}
