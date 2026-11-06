package com.ticketrush.event.hold;

import com.ticketrush.common.error.ApiException;
import com.ticketrush.common.error.ErrorCode;
import com.ticketrush.event.catalog.AvailabilityService;
import com.ticketrush.event.catalog.Event;
import com.ticketrush.event.catalog.EventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * The source of truth for seat ownership (spec §9.2). One conditional UPDATE flips AVAILABLE -> HELD under
 * InnoDB row locks; the re-check in the same transaction makes the hold all-or-nothing and idempotent per bookingId.
 */
@Service
public class HoldService {

    private static final Logger log = LoggerFactory.getLogger(HoldService.class);

    private final EventRepository events;
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final AvailabilityService availability;
    private final Clock clock;

    HoldService(EventRepository events, NamedParameterJdbcTemplate jdbc, TransactionTemplate tx,
                AvailabilityService availability, Clock clock) {
        this.events = events;
        this.jdbc = jdbc;
        this.tx = tx;
        this.availability = availability;
        this.clock = clock;
    }

    public HoldResponse hold(long eventId, HoldRequest req) {
        Event event = events.findById(eventId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Event " + eventId + " not found"));
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        if (!event.saleOpenAt(now)) {
            throw new ApiException(ErrorCode.SALE_NOT_OPEN, "Ticket sales for this event are not open");
        }
        List<Long> ids = req.seatIds().stream().sorted().toList();   // consistent lock order
        HoldResponse response;
        try {
            try {
                response = tx.execute(s -> holdInTransaction(event, req, ids, now));
            } catch (DataAccessException e) {
                if (!isDeadlock(e)) {
                    throw e;
                }
                log.info("Deadlock while holding seats for booking {}, retrying once", req.bookingId());
                response = tx.execute(s -> holdInTransaction(event, req, ids, now));
            }
        } catch (PessimisticLockingFailureException | CannotGetJdbcConnectionException | QueryTimeoutException e) {
            // Lock wait (1205), a second deadlock, or no connection in time: fail fast so the hold can't commit
            // after booking-service has given up. booking-service treats 503 as ambiguous and releases.
            log.warn("Seat hold for booking {} failed fast: {}", req.bookingId(), e.toString());
            throw new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE, "Seat inventory is busy, please retry");
        }
        availability.evict(eventId);
        return response;
    }

    /** Only a true deadlock (MySQL 1213) is retried; lock-wait timeouts (1205) are not. */
    private static boolean isDeadlock(DataAccessException e) {
        return NestedExceptionUtils.getMostSpecificCause(e) instanceof SQLException sql && sql.getErrorCode() == 1213;
    }

    private HoldResponse holdInTransaction(Event event, HoldRequest req, List<Long> ids, Instant now) {
        var params = new MapSqlParameterSource()
                .addValue("event", event.getId())
                .addValue("ids", ids)
                .addValue("booking", UuidBytes.toBytes(req.bookingId()))
                .addValue("until", Timestamp.from(now.plusSeconds(req.holdSeconds())));
        jdbc.update("""
                UPDATE seats SET status = 'HELD', booking_id = :booking, held_until = :until, updated_at = NOW(3)
                 WHERE event_id = :event AND id IN (:ids) AND status = 'AVAILABLE'""", params);
        // Same transaction: count what THIS booking now holds (new rows + rows from an earlier identical request).
        record Held(String seatNo, Instant heldUntil) {}
        List<Held> held = jdbc.query("""
                SELECT seat_no, held_until FROM seats
                 WHERE event_id = :event AND id IN (:ids) AND booking_id = :booking AND status = 'HELD'
                 ORDER BY id""", params,
                (rs, i) -> new Held(rs.getString("seat_no"), rs.getTimestamp("held_until").toInstant()));
        if (held.size() != ids.size()) {
            // some seat is taken, booked, or belongs to another event: roll back everything
            throw new ApiException(ErrorCode.SEATS_UNAVAILABLE, "One or more seats are no longer available");
        }
        Instant heldUntil = held.stream().map(Held::heldUntil).min(Instant::compareTo).orElseThrow();
        return new HoldResponse(heldUntil, event.getPriceCents() * ids.size(), held.stream().map(Held::seatNo).toList());
    }
}
