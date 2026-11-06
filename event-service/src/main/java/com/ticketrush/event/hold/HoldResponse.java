package com.ticketrush.event.hold;

import java.time.Instant;
import java.util.List;

/** Authoritative hold result: event-service decides the price and the expiry (spec §8.1 step 5, R11). */
public record HoldResponse(Instant heldUntil, long amountCents, List<String> seatNos) {
}
