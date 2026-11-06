package com.ticketrush.booking.eventclient;

import java.time.Instant;
import java.util.List;

/** Result of asking event-service to hold seats (spec §8.1 step 6). */
public sealed interface HoldOutcome {

    /** Seats are held: authoritative expiry, price and seat numbers. */
    record Held(Instant heldUntil, long amountCents, List<String> seatNos) implements HoldOutcome {}

    /** Definitive "no" (4xx): nothing was held. */
    record Rejected(int status, String code) implements HoldOutcome {}

    /**
     * Unknown (timeout, 5xx, broken response): the hold MAY have committed, so a release must be published.
     * {@code retryable}: a 5xx (event-service rolled back and holds are idempotent per booking), not a read timeout.
     */
    record Ambiguous(String reason, boolean retryable) implements HoldOutcome {}

    /** The request never reached event-service (no connection, or the circuit is open): nothing was held. */
    record NotSent(String reason) implements HoldOutcome {}
}
