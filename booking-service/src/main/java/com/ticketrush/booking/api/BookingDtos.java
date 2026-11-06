package com.ticketrush.booking.api;

import com.ticketrush.booking.domain.Booking;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

public final class BookingDtos {

    private BookingDtos() {
    }

    public record CreateBookingRequest(@Positive long eventId, @NotNull @Size(min = 1, max = 4) List<@NotNull Long> seatIds) {
        @AssertTrue(message = "must not contain duplicates")
        public boolean isSeatIdsDistinct() {
            return seatIds == null || new HashSet<>(seatIds).size() == seatIds.size();
        }
    }

    public record BookingView(UUID id, long eventId, String status, List<Long> seatIds, List<String> seatNos,
                              Long amountCents, Instant expiresAt, String failureReason, Instant createdAt) {
        public static BookingView of(Booking b) {
            return new BookingView(b.id(), b.eventId(), b.status().name(), b.seatIds(), b.seatNos(), b.amountCents(),
                    b.expiresAt(), b.failureReason(), b.createdAt());
        }
    }

    public record BookingPage(List<BookingView> content, int page, int size) {
    }
}
