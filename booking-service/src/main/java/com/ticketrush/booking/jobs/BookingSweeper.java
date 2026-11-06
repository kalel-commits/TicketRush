package com.ticketrush.booking.jobs;

import com.ticketrush.booking.api.BookingService;
import com.ticketrush.booking.domain.Booking;
import com.ticketrush.booking.domain.BookingRepository;
import com.ticketrush.booking.domain.BookingStatus;
import com.ticketrush.booking.outbox.OutboxWriter;
import com.ticketrush.booking.seatlock.SeatLocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Expires unpaid bookings (spec §12): HOLDING past its 60 s timeout -> FAILED, PENDING_PAYMENT past the hold
 * -> EXPIRED. Each transition releases quota and publishes booking.cancelled in the same transaction.
 * Runs on every instance; SKIP LOCKED + conditional updates make concurrent sweeps split the work.
 */
@Component
public class BookingSweeper {

    private static final Logger log = LoggerFactory.getLogger(BookingSweeper.class);
    private static final int BATCH = 100;

    private final BookingRepository bookings;
    private final OutboxWriter outbox;
    private final SeatLocks seatLocks;
    private final TransactionTemplate tx;

    BookingSweeper(BookingRepository bookings, OutboxWriter outbox, SeatLocks seatLocks, TransactionTemplate tx) {
        this.bookings = bookings;
        this.outbox = outbox;
        this.seatLocks = seatLocks;
        this.tx = tx;
    }

    @Scheduled(fixedDelayString = "${ticketrush.booking.sweeper-interval-ms}")
    void scheduled() {
        try {
            int n = sweep();
            if (n > 0) log.info("Sweeper expired {} bookings", n);
        } catch (RuntimeException e) {
            log.warn("Sweeper run failed, will retry: {}", e.toString());
        }
    }

    /** @return number of bookings moved to a terminal state by this call */
    public int sweep() {
        List<Booking> done = tx.execute(status -> {
            List<Booking> expired = new ArrayList<>();
            for (Booking b : bookings.lockOverdue(BATCH)) {
                boolean holding = b.status() == BookingStatus.HOLDING;
                BookingStatus to = holding ? BookingStatus.FAILED : BookingStatus.EXPIRED;
                String reason = holding ? "HOLDING_TIMEOUT" : "EXPIRED";
                if (bookings.transition(b.id(), b.status(), to, reason)) {
                    bookings.releaseQuota(b.userId(), b.eventId(), b.seatCount());
                    outbox.append(b.id(), BookingService.BOOKING_CANCELLED,
                            Map.of("bookingId", b.id().toString(), "eventId", b.eventId(), "reason", reason));
                    expired.add(b);
                }
            }
            return expired;
        });
        if (done == null) return 0;
        done.forEach(b -> seatLocks.release(b.eventId(), b.seatIds(), b.lockOwner()));   // after commit, best effort
        return done.size();
    }
}
