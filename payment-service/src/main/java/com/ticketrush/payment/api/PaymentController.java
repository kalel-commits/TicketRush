package com.ticketrush.payment.api;

import com.ticketrush.common.error.ApiException;
import com.ticketrush.common.error.ErrorCode;
import com.ticketrush.payment.api.PaymentDtos.CreatePaymentRequest;
import com.ticketrush.payment.api.PaymentDtos.PaymentView;
import com.ticketrush.payment.domain.PaymentRepository;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/payments")
class PaymentController {

    private final PaymentService service;
    private final PaymentRepository payments;

    PaymentController(PaymentService service, PaymentRepository payments) {
        this.service = service;
        this.payments = payments;
    }

    /** 200 SUCCEEDED, 202 PROCESSING (poll), 402 DECLINED, 409 / 422 / 503 problems; see PaymentResponses. */
    @PostMapping
    ResponseEntity<PaymentView> pay(@AuthenticationPrincipal Jwt jwt,
                                    @RequestHeader("Idempotency-Key") String idempotencyKey,
                                    @Valid @RequestBody CreatePaymentRequest req) {
        return PaymentResponses.of(service.pay(userId(jwt), idempotencyKey, req.bookingId()));
    }

    /** Owner only; anyone else gets 404 so payment ids can't be probed. Always 200 with the current status. */
    @GetMapping("/{id}")
    PaymentView get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return payments.findById(id).filter(p -> p.userId() == userId(jwt)).map(PaymentView::of)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Payment not found"));
    }

    private static long userId(Jwt jwt) {
        return Long.parseLong(jwt.getSubject());
    }
}
