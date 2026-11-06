package com.ticketrush.payment.domain;

import java.time.Instant;
import java.util.UUID;

/** Row view of payments. */
public record Payment(UUID id, UUID bookingId, long userId, long amountCents, PaymentStatus status,
                      String idempotencyKey, String requestHash, String providerRef, String failureReason,
                      Instant createdAt, Instant updatedAt) {}
