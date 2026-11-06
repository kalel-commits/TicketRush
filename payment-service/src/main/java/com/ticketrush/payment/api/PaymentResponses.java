package com.ticketrush.payment.api;

import com.ticketrush.common.error.ApiException;
import com.ticketrush.common.error.ErrorCode;
import com.ticketrush.payment.api.PaymentDtos.PaymentView;
import com.ticketrush.payment.domain.Payment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The HTTP answer is a pure function of the stored attempt, so a replay with the same Idempotency-Key returns
 * exactly what the first call returned (spec §8.2, §10).
 */
final class PaymentResponses {

    private PaymentResponses() {
    }

    static ResponseEntity<PaymentView> of(Payment p) {
        return switch (p.status()) {
            case PROCESSING -> ResponseEntity.status(HttpStatus.ACCEPTED).body(PaymentView.of(p));   // poll GET /api/payments/{id}
            case SUCCEEDED, REFUND_PENDING, REFUNDED -> ResponseEntity.ok(PaymentView.of(p));
            case FAILED -> throw "DECLINED".equals(p.failureReason())
                    ? new ApiException(ErrorCode.DECLINED, "The payment was declined").withProperty("paymentId", p.id())
                    : new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE, "The payment provider is unavailable, nothing was charged")
                            .withProperty("paymentId", p.id());
        };
    }
}
