package com.ticketrush.common.error;

/** Stable, client-facing error codes. Sent as the {@code code} property of every ProblemDetail. */
public enum ErrorCode {
    VALIDATION_FAILED(400),
    UNAUTHORIZED(401),
    FORBIDDEN(403),
    NOT_FOUND(404),
    EMAIL_TAKEN(409),
    INVALID_CREDENTIALS(401),
    SEATS_UNAVAILABLE(409),
    SEAT_LIMIT_EXCEEDED(409),
    SALE_NOT_OPEN(409),
    IDEMPOTENCY_KEY_REUSED(422),
    PAYMENT_IN_PROGRESS_OR_DONE(409),
    DECLINED(402),
    DEPENDENCY_UNAVAILABLE(503),
    ASSISTANT_UNAVAILABLE(503),
    INVALID_STATE(409),
    INTERNAL_ERROR(500);

    private final int status;

    ErrorCode(int status) {
        this.status = status;
    }

    public int status() {
        return status;
    }
}
