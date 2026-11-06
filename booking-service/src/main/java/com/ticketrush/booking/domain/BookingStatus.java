package com.ticketrush.booking.domain;

/** Spec §7.1. Terminal: CONFIRMED, CANCELLED, EXPIRED, FAILED. */
public enum BookingStatus {
    HOLDING, PENDING_PAYMENT, CONFIRMED, CANCELLED, EXPIRED, FAILED;

    /** Statuses that count against the per-user seat quota. */
    public boolean active() {
        return this == HOLDING || this == PENDING_PAYMENT || this == CONFIRMED;
    }
}
