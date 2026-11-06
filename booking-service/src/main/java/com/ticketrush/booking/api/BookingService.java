package com.ticketrush.booking.api;

import com.ticketrush.booking.api.BookingDtos.BookingView;
import com.ticketrush.booking.api.BookingDtos.CreateBookingRequest;
import com.ticketrush.booking.domain.Booking;
import com.ticketrush.booking.domain.BookingRepository;
import com.ticketrush.booking.domain.BookingStatus;
import com.ticketrush.booking.eventclient.EventClient;
import com.ticketrush.booking.eventclient.HoldOutcome;
import com.ticketrush.booking.outbox.OutboxWriter;
import com.ticketrush.booking.seatlock.SeatLocks;
import com.ticketrush.common.error.ApiException;
import com.ticketrush.common.error.ErrorCode;
import com.ticketrush.common.id.UuidV7;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Create-booking orchestration, spec §8.1. No DB transaction ever spans the event-service call:
 * tx#1 (quota + HOLDING row) -> hold call -> tx#2 (outcome).
 */
@Service
public class BookingService {

    public static final String BOOKING_CANCELLED = "booking.cancelled";
    private static final Logger log = LoggerFactory.getLogger(BookingService.class);

    public record Result(BookingView booking, boolean created) {}

    private final BookingRepository bookings;
    private final EventClient events;
    private final OutboxWriter outbox;
    private final SeatLocks seatLocks;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final int maxSeats;
    private final int holdSeconds;
    private final int holdingTimeoutSeconds;

    BookingService(BookingRepository bookings, EventClient events, OutboxWriter outbox, SeatLocks seatLocks,
                   TransactionTemplate tx, Clock clock,
                   @Value("${ticketrush.booking.max-seats-per-event}") int maxSeats,
                   @Value("${ticketrush.booking.hold-seconds}") int holdSeconds,
                   @Value("${ticketrush.booking.holding-timeout-seconds}") int holdingTimeoutSeconds) {
        this.bookings = bookings;
        this.events = events;
        this.outbox = outbox;
        this.seatLocks = seatLocks;
        this.tx = tx;
        this.clock = clock;
        this.maxSeats = maxSeats;
        this.holdSeconds = holdSeconds;
        this.holdingTimeoutSeconds = holdingTimeoutSeconds;
    }

    public Result create(long userId, String idempotencyKey, CreateBookingRequest req) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 64) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Idempotency-Key header must be 1-64 characters");
        }
        List<Long> seatIds = req.seatIds().stream().sorted().toList();
        String hash = requestHash(req.eventId(), seatIds);

        // 1. idempotency: a retry of the same request returns the same booking, without side effects
        var existing = bookings.findByIdempotency(userId, idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), hash);
        }

        // 2. Redis fast filter: all seats or none; losers never touch MySQL (re-entrant for this same request)
        String owner = userId + ":" + idempotencyKey;
        if (!seatLocks.acquire(req.eventId(), seatIds, owner, Duration.ofSeconds(holdSeconds + 60L))) {
            throw new ApiException(ErrorCode.SEATS_UNAVAILABLE, "One or more seats are no longer available");
        }

        // 3. tx#1: reserve quota + insert HOLDING
        UUID id = UuidV7.next();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        try {
            inTransactionWithDeadlockRetry(() -> {
                if (!bookings.reserveQuota(userId, req.eventId(), seatIds.size(), maxSeats)) {
                    throw new ApiException(ErrorCode.SEAT_LIMIT_EXCEEDED,
                            "You can hold at most " + maxSeats + " seats for this event");
                }
                bookings.insertHolding(id, userId, req.eventId(), seatIds, idempotencyKey, hash,
                        now.plusSeconds(holdingTimeoutSeconds), now);
            });
        } catch (DuplicateKeyException twinWon) {
            // a concurrent request with the same key inserted first: answer with its booking.
            // Do NOT release the locks: they carry the same owner token, i.e. they are the twin's locks.
            return replay(bookings.findByIdempotency(userId, idempotencyKey).orElseThrow(), hash);
        } catch (RuntimeException e) {
            seatLocks.release(req.eventId(), seatIds, owner);
            throw e;
        }

        // 4. hold seats in event-service (outside any transaction)
        HoldOutcome outcome = events.hold(req.eventId(), id, seatIds, holdSeconds);

        // 5. tx#2: record the outcome
        return switch (outcome) {
            case HoldOutcome.Held held -> {
                boolean moved = Boolean.TRUE.equals(tx.execute(s ->
                        bookings.markPendingPayment(id, held.amountCents(), held.heldUntil(), seatIds, held.seatNos())));
                if (!moved) {   // the sweeper failed it while we waited (and published the release)
                    throw new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE, "Booking timed out, please retry");
                }
                yield new Result(view(id), true);
            }
            case HoldOutcome.Rejected rejected -> {
                fail(id, userId, req.eventId(), seatIds.size(), rejected.code(), false);
                seatLocks.release(req.eventId(), seatIds, owner);
                throw rejection(rejected);
            }
            case HoldOutcome.NotSent notSent -> {   // nothing reached event-service: fail, but no release to publish
                log.warn("Hold for booking {} was not sent ({}); failing", id, notSent.reason());
                fail(id, userId, req.eventId(), seatIds.size(), "EVENT_SERVICE_UNAVAILABLE", false);
                seatLocks.release(req.eventId(), seatIds, owner);
                throw new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE, "Seat inventory is unavailable, please retry");
            }
            case HoldOutcome.Ambiguous ambiguous -> {
                log.warn("Hold for booking {} is ambiguous ({}); failing and releasing", id, ambiguous.reason());
                fail(id, userId, req.eventId(), seatIds.size(), "HOLD_AMBIGUOUS", true);
                seatLocks.release(req.eventId(), seatIds, owner);
                throw new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE, "Seat inventory is unavailable, please retry");
            }
        };
    }

    /** User cancel (spec §8.4): PENDING_PAYMENT -> CANCELLED, quota release and seat release, all at once. */
    public BookingView cancel(long userId, UUID id) {
        Booking b = bookings.findById(id).filter(x -> x.userId() == userId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Booking not found"));
        boolean cancelled = Boolean.TRUE.equals(tx.execute(s -> {
            if (!bookings.transition(id, BookingStatus.PENDING_PAYMENT, BookingStatus.CANCELLED, "CANCELLED_BY_USER")) {
                return false;
            }
            bookings.releaseQuota(b.userId(), b.eventId(), b.seatCount());
            outbox.append(id, BOOKING_CANCELLED, Map.of("bookingId", id.toString(), "eventId", b.eventId(), "reason", "CANCELLED"));
            return true;
        }));
        if (!cancelled) {
            throw new ApiException(ErrorCode.INVALID_STATE, "Only bookings awaiting payment can be cancelled");
        }
        seatLocks.release(b.eventId(), b.seatIds(), b.lockOwner());
        return view(id);
    }

    /** HOLDING -> FAILED + quota release (+ release event when the hold may have committed). */
    private void fail(UUID id, long userId, long eventId, int seats, String reason, boolean publishRelease) {
        tx.executeWithoutResult(s -> {
            if (bookings.transition(id, BookingStatus.HOLDING, BookingStatus.FAILED, reason)) {
                bookings.releaseQuota(userId, eventId, seats);
                if (publishRelease) {
                    outbox.append(id, BOOKING_CANCELLED, Map.of("bookingId", id.toString(), "eventId", eventId, "reason", reason));
                }
            }
        });
    }

    private Result replay(Booking b, String hash) {
        if (!b.requestHash().equals(hash)) {
            throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED, "This Idempotency-Key was used for a different request");
        }
        return new Result(BookingView.of(b), false);
    }

    private static ApiException rejection(HoldOutcome.Rejected r) {
        ErrorCode code = switch (r.code()) {
            case "SEATS_UNAVAILABLE" -> ErrorCode.SEATS_UNAVAILABLE;
            case "SALE_NOT_OPEN" -> ErrorCode.SALE_NOT_OPEN;
            case "NOT_FOUND" -> ErrorCode.NOT_FOUND;
            default -> r.status() == 404 ? ErrorCode.NOT_FOUND : ErrorCode.VALIDATION_FAILED;
        };
        return new ApiException(code, switch (code) {
            case SEATS_UNAVAILABLE -> "One or more seats are no longer available";
            case SALE_NOT_OPEN -> "Ticket sales for this event are not open";
            case NOT_FOUND -> "Event not found";
            default -> "Seat request was rejected";
        });
    }

    private void inTransactionWithDeadlockRetry(Runnable work) {
        try {
            tx.executeWithoutResult(s -> work.run());
        } catch (PessimisticLockingFailureException deadlock) {   // quota-row upserts can deadlock under contention
            tx.executeWithoutResult(s -> work.run());
        }
    }

    BookingView view(UUID id) {
        return bookings.findById(id).map(BookingView::of).orElseThrow();
    }

    static String requestHash(long eventId, List<Long> sortedSeatIds) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((eventId + ":" + sortedSeatIds).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
