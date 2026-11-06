package com.ticketrush.payment.api;

import com.ticketrush.common.error.ApiException;
import com.ticketrush.common.error.ErrorCode;
import com.ticketrush.common.id.UuidV7;
import com.ticketrush.payment.bookingclient.BookingClient;
import com.ticketrush.payment.bookingclient.BookingClient.BookingLookup;
import com.ticketrush.payment.domain.Payment;
import com.ticketrush.payment.domain.PaymentRepository;
import com.ticketrush.payment.domain.PaymentStatus;
import com.ticketrush.payment.outbox.OutboxWriter;
import com.ticketrush.payment.provider.ProviderClient;
import com.ticketrush.payment.provider.ProviderClient.ChargeOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/**
 * Pay, spec §8.2: idempotency -> validate booking -> insert PROCESSING (the unique index stops a second attempt
 * before money moves) -> charge (no transaction open) -> record the outcome.
 */
@Service
public class PaymentService {

    public static final String PAYMENT_SUCCEEDED = "payment.succeeded";
    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository payments;
    private final BookingClient bookings;
    private final ProviderClient provider;
    private final OutboxWriter outbox;
    private final TransactionTemplate tx;
    private final Clock clock;

    PaymentService(PaymentRepository payments, BookingClient bookings, ProviderClient provider, OutboxWriter outbox,
                   TransactionTemplate tx, Clock clock) {
        this.payments = payments;
        this.bookings = bookings;
        this.provider = provider;
        this.outbox = outbox;
        this.tx = tx;
        this.clock = clock;
    }

    public Payment pay(long userId, String idempotencyKey, UUID bookingId) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 64) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Idempotency-Key header must be 1-64 characters");
        }
        String hash = requestHash(bookingId);

        // 1. idempotency: a retry of the same request gets the same attempt, whatever state it is in
        var existing = payments.findByIdempotency(userId, idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), hash);
        }

        // 2. the booking must be this user's, awaiting payment and not expired; the amount comes from it
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        BookingLookup.Found booking = switch (bookings.get(bookingId)) {
            case BookingLookup.Found f when f.userId() == userId -> f;
            case BookingLookup.Found f -> throw notFound();
            case BookingLookup.NotFound n -> throw notFound();
            case BookingLookup.Unavailable u -> throw new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE, "Bookings are unavailable, please retry");
        };
        if (!"PENDING_PAYMENT".equals(booking.status()) || !booking.expiresAt().isAfter(now)) {
            throw new ApiException(ErrorCode.INVALID_STATE, "This booking can no longer be paid");
        }

        // 3. PROCESSING first: a second concurrent attempt for this booking fails here, before any money moves
        Payment attempt = new Payment(UuidV7.next(), bookingId, userId, booking.amountCents(), PaymentStatus.PROCESSING,
                idempotencyKey, hash, null, null, now, now);
        for (int attemptNo = 1; ; attemptNo++) {
            try {
                payments.insertProcessing(attempt);
                break;
            } catch (DuplicateKeyException e) {
                var twin = payments.findByIdempotency(userId, idempotencyKey);
                if (twin.isPresent()) {
                    return replay(twin.get(), hash);   // same key fired twice concurrently
                }
                var active = payments.findActiveByBooking(bookingId);
                if (active.isEmpty() && attemptNo == 1) {
                    continue;   // the attempt that blocked us has just FAILED: the booking is free again, try once more
                }
                throw new ApiException(ErrorCode.PAYMENT_IN_PROGRESS_OR_DONE, "This booking already has a payment in progress or completed")
                        .withProperty("paymentId", active.map(Payment::id).orElse(null));
            }
        }

        // 4. charge, outside any transaction; provider idempotency key = payment id
        ChargeOutcome outcome = provider.charge(attempt.id(), attempt.amountCents(), bookingId);

        // 5. record the outcome
        switch (outcome) {
            case ChargeOutcome.Approved approved -> tx.executeWithoutResult(s -> {
                if (payments.transition(attempt.id(), PaymentStatus.PROCESSING, PaymentStatus.SUCCEEDED, approved.chargeId(), null)) {
                    outbox.append(bookingId, PAYMENT_SUCCEEDED, Map.of("paymentId", attempt.id().toString(),
                            "bookingId", bookingId.toString(), "amountCents", attempt.amountCents()));
                }
            });
            case ChargeOutcome.Declined declined ->
                    payments.transition(attempt.id(), PaymentStatus.PROCESSING, PaymentStatus.FAILED, null, "DECLINED");
            case ChargeOutcome.NotSent notSent -> {
                log.warn("Payment {} not sent to the provider ({}): failing it", attempt.id(), notSent.reason());
                payments.transition(attempt.id(), PaymentStatus.PROCESSING, PaymentStatus.FAILED, null, "PROVIDER_UNAVAILABLE");
            }
            case ChargeOutcome.Ambiguous ambiguous ->   // money may have moved: leave PROCESSING for the reconciler (plan 5)
                    log.warn("Payment {} is ambiguous ({}): left PROCESSING", attempt.id(), ambiguous.reason());
        }
        return payments.findById(attempt.id()).orElseThrow();
    }

    private static Payment replay(Payment p, String hash) {
        if (!p.requestHash().equals(hash)) {
            throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED, "Idempotency-Key was already used for a different request");
        }
        return p;
    }

    private static ApiException notFound() {
        return new ApiException(ErrorCode.NOT_FOUND, "Booking not found");
    }

    static String requestHash(UUID bookingId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bookingId.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
