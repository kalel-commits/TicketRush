package com.ticketrush.booking.api;

import com.ticketrush.booking.domain.Booking;
import com.ticketrush.booking.domain.BookingRepository;
import com.ticketrush.common.error.ApiException;
import com.ticketrush.common.error.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Service-token only. Used by payment-service (validate before charging) and the reconcilers (plan 5). */
@RestController
@RequestMapping("/internal/bookings")
class InternalBookingController {

    record InternalBooking(UUID id, long userId, long eventId, String status, Long amountCents, Instant expiresAt, UUID paymentId) {
        static InternalBooking of(Booking b) {
            return new InternalBooking(b.id(), b.userId(), b.eventId(), b.status().name(), b.amountCents(), b.expiresAt(), b.paymentId());
        }
    }

    record StatusRequest(@NotNull @Size(min = 1, max = 200) List<@NotNull UUID> bookingIds) {}

    record BookingStatusView(UUID bookingId, String status, UUID paymentId) {}

    private final BookingRepository bookings;

    InternalBookingController(BookingRepository bookings) {
        this.bookings = bookings;
    }

    @GetMapping("/{id}")
    InternalBooking get(@PathVariable UUID id) {
        return bookings.findById(id).map(InternalBooking::of)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Booking not found"));
    }

    @PostMapping("/status")
    List<BookingStatusView> status(@Valid @RequestBody StatusRequest req) {
        return bookings.findAll(req.bookingIds()).stream()
                .map(b -> new BookingStatusView(b.id(), b.status().name(), b.paymentId())).toList();
    }
}
