package com.ticketrush.event;

import com.ticketrush.common.id.UuidV7;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class HoldIT extends IntegrationTestBase {

    ResultActions hold(long eventId, UUID bookingId, List<Long> seatIds, int holdSeconds) throws Exception {
        String body = json.writeValueAsString(Map.of("bookingId", bookingId.toString(), "seatIds", seatIds, "holdSeconds", holdSeconds));
        return mvc.perform(post("/internal/events/" + eventId + "/holds").header(AUTHORIZATION, service())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    List<Long> seatIds(long eventId) {
        return jdbc.queryForList("SELECT id FROM seats WHERE event_id=? ORDER BY id", Long.class, eventId);
    }

    String seatStatus(long seatId) {
        return jdbc.queryForObject("SELECT status FROM seats WHERE id=?", String.class, seatId);
    }

    @Test
    void holdSucceedsAndReturnsAmountAndSeatNumbers() throws Exception {
        long e = createEventId(1, 4);
        List<Long> s = seatIds(e);
        UUID b = UuidV7.next();
        hold(e, b, List.of(s.get(1), s.get(0)), 600).andExpect(status().isOk())
                .andExpect(jsonPath("$.amountCents").value(5000))
                .andExpect(jsonPath("$.seatNos[0]").value("A1"))
                .andExpect(jsonPath("$.seatNos[1]").value("A2"))
                .andExpect(jsonPath("$.heldUntil").exists());
        assertThat(seatStatus(s.get(0))).isEqualTo("HELD");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM seats WHERE event_id=? AND booking_id=UUID_TO_BIN(?)", Integer.class, e, b.toString()))
                .isEqualTo(2);
    }

    @Test
    void heldUntilIsRealUtcTimePlusHoldSeconds() throws Exception {
        long e = createEventId(1, 1);
        long seat = seatIds(e).getFirst();
        String body = hold(e, UuidV7.next(), List.of(seat), 600).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long fromResponse = Instant.parse(json.readTree(body).get("heldUntil").asString()).getEpochSecond() - Instant.now().getEpochSecond();
        assertThat(fromResponse).isBetween(590L, 605L);
        Long inDb = jdbc.queryForObject("SELECT TIMESTAMPDIFF(SECOND, UTC_TIMESTAMP(3), held_until) FROM seats WHERE id=?", Long.class, seat);
        assertThat(inDb).isBetween(590L, 605L);      // stored as UTC, comparable with NOW(3)/UTC_TIMESTAMP in SQL
    }

    @Test
    void lockedSeatFailsFastWith503InsteadOfCommittingLate() throws Exception {
        long e = createEventId(1, 1);
        long seat = seatIds(e).getFirst();
        try (var conn = java.sql.DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {
            conn.setAutoCommit(false);
            conn.createStatement().executeQuery("SELECT id FROM seats WHERE id=" + seat + " FOR UPDATE");   // hold the row lock
            long started = System.nanoTime();
            hold(e, UuidV7.next(), List.of(seat), 600).andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"));
            assertThat(java.time.Duration.ofNanos(System.nanoTime() - started)).isLessThan(java.time.Duration.ofSeconds(2));
            conn.rollback();
        }
        assertThat(seatStatus(seat)).isEqualTo("AVAILABLE");
    }

    @Test
    void nonNumericIdsAre400() throws Exception {
        mvc.perform(post("/internal/events/abc/holds").header(AUTHORIZATION, service())
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/events/abc"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void replaySameBookingReturnsOriginalHold() throws Exception {
        long e = createEventId(1, 2);
        List<Long> s = seatIds(e);
        UUID b = UuidV7.next();
        String first = hold(e, b, s, 600).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Thread.sleep(20);
        String second = hold(e, b, s, 600).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(second).get("heldUntil").asString()).isEqualTo(json.readTree(first).get("heldUntil").asString());
    }

    @Test
    void overlappingHoldIsAllOrNothing() throws Exception {
        long e = createEventId(1, 3);
        List<Long> s = seatIds(e);
        hold(e, UuidV7.next(), List.of(s.get(1)), 600).andExpect(status().isOk());
        hold(e, UuidV7.next(), List.of(s.get(0), s.get(1)), 600).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SEATS_UNAVAILABLE"));
        assertThat(seatStatus(s.get(0))).isEqualTo("AVAILABLE");
    }

    @Test
    void seatFromAnotherEventIsRejected() throws Exception {
        long e1 = createEventId(1, 1);
        long e2 = createEventId(1, 1);
        long foreign = seatIds(e2).getFirst();
        hold(e1, UuidV7.next(), List.of(foreign), 600).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SEATS_UNAVAILABLE"));
        assertThat(seatStatus(foreign)).isEqualTo("AVAILABLE");
    }

    @Test
    void saleWindowIsEnforced() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Map<String, Object> notYet = eventBody(1, 1);
        notYet.put("saleOpensAt", now.plus(1, ChronoUnit.DAYS).toString());
        Map<String, Object> closed = eventBody(1, 1);
        closed.put("saleOpensAt", now.minus(2, ChronoUnit.HOURS).toString());
        closed.put("saleClosesAt", now.minus(1, ChronoUnit.HOURS).toString());
        for (Map<String, Object> body : List.of(notYet, closed)) {
            long e = json.readTree(createEvent(body, admin()).andReturn().getResponse().getContentAsString()).get("id").asLong();
            hold(e, UuidV7.next(), seatIds(e), 600).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SALE_NOT_OPEN"));
        }
    }

    @Test
    void badInputIsRejected() throws Exception {
        long e = createEventId(1, 5);
        List<Long> s = seatIds(e);
        hold(e, UuidV7.next(), s, 600).andExpect(status().isBadRequest());                                   // 5 seats
        hold(e, UuidV7.next(), List.of(s.get(0), s.get(0)), 600).andExpect(status().isBadRequest());         // duplicates
        hold(e, UuidV7.next(), List.of(), 600).andExpect(status().isBadRequest());                           // none
        hold(e, UuidV7.next(), List.of(s.get(0)), 10).andExpect(status().isBadRequest());                    // hold too short
        hold(99999999L, UuidV7.next(), List.of(s.get(0)), 600).andExpect(status().isNotFound());
    }
}
