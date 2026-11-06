package com.ticketrush.payment.domain;

/** Spec §7.3. Only PROCESSING -> FAILED frees the booking's "active attempt" slot. */
public enum PaymentStatus { PROCESSING, SUCCEEDED, FAILED, REFUND_PENDING, REFUNDED }
