package com.ticketrush.event.catalog;

import com.ticketrush.event.catalog.EventDtos.CreateEventRequest;
import com.ticketrush.event.catalog.EventDtos.EventView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class EventAdminService {

    private final EventRepository events;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    EventAdminService(EventRepository events, JdbcTemplate jdbc, Clock clock) {
        this.events = events;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Saves the event and generates rows x seatsPerRow seats (A1..Z50) in one transaction. */
    @Transactional
    public EventView create(CreateEventRequest req) {
        Instant now = clock.instant();
        int total = req.rows() * req.seatsPerRow();
        Event event = events.saveAndFlush(new Event(req.name().strip(), req.venue().strip(), req.startsAt(),
                req.saleOpensAt(), req.effectiveSaleClosesAt(), req.priceCents(), total, now)
                .describedAs(req.listing(), strip(req.artist()), req.category(), strip(req.city()), strip(req.description())));

        List<Object[]> seats = new ArrayList<>(total);
        Timestamp ts = Timestamp.from(now);
        for (int r = 0; r < req.rows(); r++) {
            char row = (char) ('A' + r);
            for (int n = 1; n <= req.seatsPerRow(); n++) {
                seats.add(new Object[]{event.getId(), row + String.valueOf(n), ts});
            }
        }
        jdbc.batchUpdate("INSERT INTO seats (event_id, seat_no, status, updated_at) VALUES (?, ?, 'AVAILABLE', ?)", seats);
        return EventView.of(event, total);
    }

    private static String strip(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
