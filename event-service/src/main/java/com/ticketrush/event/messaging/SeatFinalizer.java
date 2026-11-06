package com.ticketrush.event.messaging;

import com.ticketrush.event.hold.UuidBytes;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Applies booking outcomes to seats exactly once (spec §8.3–8.4, §11.3): the processed_events insert and the
 * seat update commit together. Every update is keyed by booking_id AND status='HELD', so a late or duplicate
 * message can never touch seats that now belong to someone else.
 */
@Service
public class SeatFinalizer {

    private static final Logger log = LoggerFactory.getLogger(SeatFinalizer.class);

    private final JdbcTemplate jdbc;
    private final Counter invariantViolations;

    SeatFinalizer(JdbcTemplate jdbc, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.invariantViolations = meters.counter("event.invariant.violations");
    }

    /** @return false if this message was already processed */
    @Transactional
    public boolean confirm(UUID messageId, UUID bookingId, long eventId, int expectedSeats) {
        if (!markProcessed(messageId, "booking.confirmed")) {
            return false;
        }
        byte[] booking = UuidBytes.toBytes(bookingId);
        int booked = jdbc.update("""
                UPDATE seats SET status = 'BOOKED', held_until = NULL, updated_at = NOW(3)
                 WHERE booking_id = ? AND event_id = ? AND status = 'HELD'""", booking, eventId);
        if (booked < expectedSeats) {
            Integer alreadyBooked = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM seats WHERE booking_id = ? AND event_id = ? AND status = 'BOOKED'",
                    Integer.class, booking, eventId);
            if (alreadyBooked == null || alreadyBooked < expectedSeats) {
                // Unreachable by design (spec §9.5): a confirmed booking's seats are never released. Alert, change nothing more.
                invariantViolations.increment();
                log.error("INVARIANT VIOLATION: booking {} confirmed but only {}/{} seats of event {} are booked",
                        bookingId, alreadyBooked, expectedSeats, eventId);
            }
        }
        return true;
    }

    /** @return false if this message was already processed */
    @Transactional
    public boolean release(UUID messageId, UUID bookingId, long eventId) {
        if (!markProcessed(messageId, "booking.cancelled")) {
            return false;
        }
        jdbc.update("""
                UPDATE seats SET status = 'AVAILABLE', booking_id = NULL, held_until = NULL, updated_at = NOW(3)
                 WHERE booking_id = ? AND event_id = ? AND status = 'HELD'""", UuidBytes.toBytes(bookingId), eventId);
        return true;
    }

    private boolean markProcessed(UUID messageId, String topic) {
        return jdbc.update("INSERT IGNORE INTO processed_events (event_id, topic, processed_at) VALUES (?, ?, NOW(3))",
                UuidBytes.toBytes(messageId), topic) == 1;
    }
}
