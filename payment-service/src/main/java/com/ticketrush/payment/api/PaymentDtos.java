package com.ticketrush.payment.api;

import com.ticketrush.payment.domain.Payment;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.UUID;

public final class PaymentDtos {

    private PaymentDtos() {
    }

    public record CreatePaymentRequest(@NotNull UUID bookingId) {}

    public record PaymentView(UUID id, UUID bookingId, long amountCents, String status, String failureReason, Instant createdAt) {
        static PaymentView of(Payment p) {
            return new PaymentView(p.id(), p.bookingId(), p.amountCents(), p.status().name(), p.failureReason(), p.createdAt());
        }
    }
}
