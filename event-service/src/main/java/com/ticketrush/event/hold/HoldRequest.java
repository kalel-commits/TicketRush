package com.ticketrush.event.hold;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/** Body of POST /internal/events/{eventId}/holds (sent by booking-service). */
public record HoldRequest(
        @NotNull UUID bookingId,
        @NotNull @Size(min = 1, max = 4) List<@NotNull Long> seatIds,
        @Min(30) @Max(900) int holdSeconds) {

    @AssertTrue(message = "must not contain duplicates")
    public boolean isSeatIdsDistinct() {
        return seatIds == null || new HashSet<>(seatIds).size() == seatIds.size();
    }
}
