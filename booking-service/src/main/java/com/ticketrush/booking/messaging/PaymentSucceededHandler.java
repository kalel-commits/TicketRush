package com.ticketrush.booking.messaging;

import com.ticketrush.booking.domain.Booking;
import com.ticketrush.booking.domain.BookingRepository;
import com.ticketrush.booking.domain.BookingStatus;
import com.ticketrush.booking.outbox.OutboxWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Spec §8.3: a successful payment confirms the booking if (and only if) it is still PENDING_PAYMENT and not
 * expired; any other case is compensated with a refund request. Dedupe + transition + outbox in one transaction.
 */
@Service
public class PaymentSucceededHandler {

    public static final String TOPIC = "payment.succeeded";
    public static final String BOOKING_CONFIRMED = "booking.confirmed";
    public static final String REFUND_REQUESTED = "payment.refund.requested";
    private static final Logger log = LoggerFactory.getLogger(PaymentSucceededHandler.class);

    public enum Outcome { CONFIRMED, ALREADY_CONFIRMED, REFUND_REQUESTED, DUPLICATE }

    private final BookingRepository bookings;
    private final OutboxWriter outbox;

    PaymentSucceededHandler(BookingRepository bookings, OutboxWriter outbox) {
        this.bookings = bookings;
        this.outbox = outbox;
    }

    @Transactional
    public Outcome handle(UUID messageId, UUID paymentId, UUID bookingId) {
        if (!bookings.markProcessed(messageId, TOPIC)) {
            return Outcome.DUPLICATE;
        }
        if (bookings.confirm(bookingId, paymentId)) {
            Booking b = bookings.findById(bookingId).orElseThrow();
            outbox.append(bookingId, BOOKING_CONFIRMED,
                    Map.of("bookingId", bookingId.toString(), "eventId", b.eventId(), "seatIds", b.seatIds()));
            return Outcome.CONFIRMED;
        }
        Optional<Booking> current = bookings.findById(bookingId);
        if (current.isPresent() && current.get().status() == BookingStatus.CONFIRMED && paymentId.equals(current.get().paymentId())) {
            return Outcome.ALREADY_CONFIRMED;   // same payment seen again under a new message id
        }
        String reason = current.map(b -> "BOOKING_" + b.status().name()).orElse("UNKNOWN_BOOKING");
        log.info("Payment {} arrived for booking {} in state {}: requesting refund", paymentId, bookingId, reason);
        outbox.append(bookingId, REFUND_REQUESTED,
                Map.of("paymentId", paymentId.toString(), "bookingId", bookingId.toString(), "reason", reason));
        return Outcome.REFUND_REQUESTED;
    }
}
