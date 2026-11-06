package com.ticketrush.event;

import com.ticketrush.event.reconcile.SeatReconciler;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Spec §12 seat reconciler: owner's state decides; only stale HELD seats of the asked booking are touched. */
class SeatReconcilerIT extends IntegrationTestBase {

    @Autowired
    SeatReconciler reconciler;
    @Autowired
    MeterRegistry meters;

    long event;
    List<Long> seats;

    @BeforeEach
    void setUp() throws Exception {
        BOOKINGS.reset();
        event = createEventId(1, 6);
        seats = jdbc.queryForList("SELECT id FROM seats WHERE event_id=? ORDER BY id", Long.class, event);
    }

    /** Hold a seat for a booking; stale = held_until an hour ago (well past the 2-minute grace, whatever the clock). */
    void hold(long seat, UUID booking, boolean stale) {
        jdbc.update("UPDATE seats SET status='HELD', booking_id=UUID_TO_BIN(?), held_until=NOW(3) + INTERVAL ? HOUR WHERE id=?",
                booking.toString(), stale ? -1 : 1, seat);
    }

    String status(long seat) {
        return jdbc.queryForObject("SELECT status FROM seats WHERE id=?", String.class, seat);
    }

    String holder(long seat) {
        return jdbc.queryForObject("SELECT BIN_TO_UUID(booking_id) FROM seats WHERE id=?", String.class, seat);
    }

    @Test
    void confirmedBookingHealsALostConfirmation() {
        UUID b = UUID.randomUUID();
        hold(seats.get(0), b, true);
        BOOKINGS.put(b, "CONFIRMED");
        double before = meters.counter("reconciler.seats", "action", "booked").count();

        reconciler.reconcile();

        assertThat(status(seats.get(0))).isEqualTo("BOOKED");
        assertThat(jdbc.queryForObject("SELECT held_until FROM seats WHERE id=?", Object.class, seats.get(0))).isNull();
        assertThat(meters.counter("reconciler.seats", "action", "booked").count()).isEqualTo(before + 1);
    }

    @Test
    void terminalOrUnknownBookingsAreReleased() {
        UUID expired = UUID.randomUUID(), unknown = UUID.randomUUID();
        hold(seats.get(0), expired, true);
        hold(seats.get(1), unknown, true);
        BOOKINGS.put(expired, "EXPIRED");

        reconciler.reconcile();

        assertThat(status(seats.get(0))).isEqualTo("AVAILABLE");
        assertThat(holder(seats.get(0))).isNull();
        assertThat(status(seats.get(1))).isEqualTo("AVAILABLE");
    }

    @Test
    void inFlightBookingsAndFreshHoldsAreLeftAlone() {
        UUID pending = UUID.randomUUID(), fresh = UUID.randomUUID();
        hold(seats.get(0), pending, true);
        hold(seats.get(1), fresh, false);
        BOOKINGS.put(pending, "PENDING_PAYMENT");

        reconciler.reconcile();

        assertThat(status(seats.get(0))).isEqualTo("HELD");
        assertThat(status(seats.get(1))).isEqualTo("HELD");
        assertThat(BOOKINGS.requestBodies).noneMatch(body -> body.contains(fresh.toString()));
    }

    @Test
    void reconcilingAnOldBookingNeverTouchesASeatReHeldByANewOne() {
        UUID oldBooking = UUID.randomUUID(), newBooking = UUID.randomUUID();
        hold(seats.get(0), oldBooking, true);   // the old booking's other seat, still stale
        hold(seats.get(1), newBooking, false);  // released earlier and re-held by someone else
        BOOKINGS.put(oldBooking, "EXPIRED");
        BOOKINGS.put(newBooking, "PENDING_PAYMENT");

        reconciler.reconcile();

        assertThat(status(seats.get(0))).isEqualTo("AVAILABLE");
        assertThat(status(seats.get(1))).isEqualTo("HELD");
        assertThat(holder(seats.get(1))).isEqualTo(newBooking.toString());
    }

    @Test
    void bookingServiceDownChangesNothing() {
        UUID b = UUID.randomUUID();
        hold(seats.get(0), b, true);
        BOOKINGS.put(b, "EXPIRED");
        BOOKINGS.failing = true;

        SeatReconciler.Result result = reconciler.reconcile();

        assertThat(result).isEqualTo(new SeatReconciler.Result(0, 0));
        assertThat(status(seats.get(0))).isEqualTo("HELD");
    }

    @Test
    void callsBookingServiceWithAServiceToken() {
        UUID b = UUID.randomUUID();
        hold(seats.get(0), b, true);
        BOOKINGS.put(b, "PENDING_PAYMENT");

        reconciler.reconcile();

        assertThat(BOOKINGS.authHeaders).isNotEmpty().allMatch(h -> h.startsWith("Bearer "));
    }
}
