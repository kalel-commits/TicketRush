package com.ticketrush.event;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class EventAdminIT extends IntegrationTestBase {

    @Test
    void adminCreatesEventAndSeatsAreGenerated() throws Exception {
        String body = createEvent(eventBody(3, 4), admin()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalSeats").value(12))
                .andExpect(jsonPath("$.availableSeats").value(12))
                .andReturn().getResponse().getContentAsString();
        long id = json.readTree(body).get("id").asLong();
        List<String> seatNos = jdbc.queryForList("SELECT seat_no FROM seats WHERE event_id=? ORDER BY id", String.class, id);
        assertThat(seatNos).hasSize(12).startsWith("A1", "A2", "A3", "A4", "B1").endsWith("C4");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM seats WHERE event_id=? AND status='AVAILABLE'", Integer.class, id)).isEqualTo(12);
    }

    @Test
    void saleClosesDefaultsToStart() throws Exception {
        Map<String, Object> b = eventBody(1, 1);
        createEvent(b, admin()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.saleClosesAt").value(b.get("startsAt")));
    }

    @Test
    void onlyAdminsCanCreate() throws Exception {
        createEvent(eventBody(1, 1), user()).andExpect(status().isForbidden());
        createEvent(eventBody(1, 1), null).andExpect(status().isUnauthorized());
        createEvent(eventBody(1, 1), service()).andExpect(status().isUnauthorized());
    }

    @Test
    void invalidInputIsRejected() throws Exception {
        record Case(String field, Object value) {}
        Instant now = Instant.now();
        for (Case c : List.of(new Case("name", " "), new Case("venue", ""), new Case("priceCents", 0),
                new Case("rows", 0), new Case("rows", 27), new Case("seatsPerRow", 51), new Case("startsAt", null))) {
            Map<String, Object> b = eventBody(1, 1);
            b.put(c.field(), c.value());
            createEvent(b, admin()).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors." + c.field()).exists());
        }
        Map<String, Object> b = eventBody(1, 1);
        b.put("saleOpensAt", now.plus(40, ChronoUnit.DAYS).toString());   // after startsAt (= default close)
        createEvent(b, admin()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.saleOpensAt").exists());
    }
}
