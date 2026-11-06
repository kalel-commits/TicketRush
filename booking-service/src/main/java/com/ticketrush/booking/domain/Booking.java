package com.ticketrush.booking.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Read view of one bookings row plus its seats (ordered by seat id). */
public record Booking(UUID id, long userId, long eventId, int seatCount, Long amountCents, BookingStatus status,
                      String idempotencyKey, String requestHash, Instant expiresAt, String failureReason,
                      UUID paymentId, Instant createdAt, List<Long> seatIds, List<String> seatNos) {

    /** Owner token for the Redis seat locks (spec §8.1): the same request always maps to the same token. */
    public String lockOwner() {
        return userId + ":" + idempotencyKey;
    }
}
