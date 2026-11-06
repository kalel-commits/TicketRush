package com.ticketrush.event.reconcile;

import com.ticketrush.event.catalog.AvailabilityService;
import com.ticketrush.event.hold.UuidBytes;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Spec §12 seat reconciler. Seats HELD well past their hold are settled by asking the owner (booking-service):
 * CONFIRMED -> BOOKED (heals a lost booking.confirmed); terminal or unknown -> released; still in flight -> skipped.
 * No row lock is held across the HTTP call: every change is a conditional UPDATE keyed by the booking id and
 * status='HELD', so a seat already handled by a consumer, or re-held by another booking, is never touched.
 */
@Component
public class SeatReconciler {

    public record Result(int booked, int released) {}

    private static final Logger log = LoggerFactory.getLogger(SeatReconciler.class);
    private static final Set<String> TERMINAL = Set.of("FAILED", "CANCELLED", "EXPIRED");

    private final JdbcTemplate jdbc;
    private final BookingStatusClient bookings;
    private final AvailabilityService availability;
    private final MeterRegistry meters;

    SeatReconciler(JdbcTemplate jdbc, BookingStatusClient bookings, AvailabilityService availability, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.bookings = bookings;
        this.availability = availability;
        this.meters = meters;
    }

    @Scheduled(fixedDelayString = "${ticketrush.reconciler.seat-interval-ms}", initialDelayString = "${ticketrush.reconciler.seat-initial-delay-ms}")
    void scheduled() {
        try {
            Result r = reconcile();
            if (r.booked() + r.released() > 0) log.info("Seat reconciler booked {} and released {} seats", r.booked(), r.released());
        } catch (RuntimeException e) {
            log.warn("Seat reconciler run failed, will retry: {}", e.toString());
        }
    }

    public Result reconcile() {
        record Candidate(UUID bookingId, long eventId) {}
        List<Candidate> candidates = jdbc.query("""
                SELECT DISTINCT BIN_TO_UUID(booking_id) AS booking, event_id FROM seats
                 WHERE status = 'HELD' AND held_until < NOW(3) - INTERVAL 2 MINUTE LIMIT 200""",
                (rs, i) -> new Candidate(UUID.fromString(rs.getString("booking")), rs.getLong("event_id")));
        if (candidates.isEmpty()) return new Result(0, 0);

        var answer = bookings.statuses(candidates.stream().map(Candidate::bookingId).distinct().toList());
        if (!(answer instanceof BookingStatusClient.StatusLookup.Found found)) {
            log.warn("Seat reconciler skipped {} stale bookings: booking-service unavailable ({})", candidates.size(), answer);
            return new Result(0, 0);
        }
        int booked = 0, released = 0;
        Set<Long> changedEvents = new HashSet<>();
        for (Candidate c : candidates) {
            String status = found.statuses().get(c.bookingId());
            int n;
            if ("CONFIRMED".equals(status)) {
                n = jdbc.update("UPDATE seats SET status = 'BOOKED', held_until = NULL, updated_at = NOW(3) "
                        + "WHERE booking_id = ? AND event_id = ? AND status = 'HELD'", UuidBytes.toBytes(c.bookingId()), c.eventId());
                booked += n;
                meters.counter("reconciler.seats", "action", "booked").increment(n);
            } else if (status == null || TERMINAL.contains(status)) {
                n = jdbc.update("UPDATE seats SET status = 'AVAILABLE', booking_id = NULL, held_until = NULL, updated_at = NOW(3) "
                        + "WHERE booking_id = ? AND event_id = ? AND status = 'HELD'", UuidBytes.toBytes(c.bookingId()), c.eventId());
                released += n;
                meters.counter("reconciler.seats", "action", "released").increment(n);
            } else {
                n = 0;   // HOLDING / PENDING_PAYMENT: the booking's own expiry will settle it
            }
            if (n > 0) changedEvents.add(c.eventId());
        }
        changedEvents.forEach(availability::evict);
        return new Result(booked, released);
    }
}
