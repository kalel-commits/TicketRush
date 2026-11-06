package com.ticketrush.booking.api;

import com.ticketrush.booking.api.BookingDtos.BookingPage;
import com.ticketrush.booking.api.BookingDtos.BookingView;
import com.ticketrush.booking.api.BookingDtos.CreateBookingRequest;
import com.ticketrush.booking.domain.BookingRepository;
import com.ticketrush.common.error.ApiException;
import com.ticketrush.common.error.ErrorCode;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/bookings")
class BookingController {

    private final BookingService service;
    private final BookingRepository bookings;

    BookingController(BookingService service, BookingRepository bookings) {
        this.service = service;
        this.bookings = bookings;
    }

    @PostMapping
    ResponseEntity<BookingView> create(@AuthenticationPrincipal Jwt jwt,
                                       @RequestHeader("Idempotency-Key") String idempotencyKey,
                                       @Valid @RequestBody CreateBookingRequest req) {
        var result = service.create(userId(jwt), idempotencyKey, req);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.booking());
    }

    @PostMapping("/{id}/cancel")
    BookingView cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.cancel(userId(jwt), id);
    }

    @GetMapping("/me")
    BookingPage mine(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "0") int page,
                     @RequestParam(defaultValue = "20") int size) {
        int p = Math.max(page, 0);
        int s = Math.min(Math.max(size, 1), 50);
        return new BookingPage(bookings.findByUser(userId(jwt), p, s).stream().map(BookingView::of).toList(), p, s);
    }

    /** Owner only; anyone else gets 404 so booking ids can't be probed. */
    @GetMapping("/{id}")
    BookingView get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return bookings.findById(id).filter(b -> b.userId() == userId(jwt)).map(BookingView::of)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Booking not found"));
    }

    static long userId(Jwt jwt) {
        return Long.parseLong(jwt.getSubject());
    }
}
