package com.ticketrush.payment.messaging;

import com.ticketrush.payment.domain.Payment;
import com.ticketrush.payment.domain.PaymentRepository;
import com.ticketrush.payment.domain.PaymentStatus;
import com.ticketrush.payment.provider.ProviderClient;
import com.ticketrush.payment.provider.ProviderClient.RefundOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/**
 * payment.refund.requested (spec §8.4): dedupe + SUCCEEDED -> REFUND_PENDING in one transaction; only the caller
 * that won that transition calls the provider (idempotency key refund-<paymentId>), outside any transaction;
 * then REFUND_PENDING -> REFUNDED. A provider failure leaves REFUND_PENDING for the reconciler (plan 5).
 */
@Component
public class RefundHandler {

    public static final String TOPIC = "payment.refund.requested";
    private static final Logger log = LoggerFactory.getLogger(RefundHandler.class);

    public enum Outcome { REFUNDED, REFUND_PENDING, DUPLICATE, IGNORED }

    private final PaymentRepository payments;
    private final ProviderClient provider;
    private final TransactionTemplate tx;

    RefundHandler(PaymentRepository payments, ProviderClient provider, TransactionTemplate tx) {
        this.payments = payments;
        this.provider = provider;
        this.tx = tx;
    }

    public Outcome handle(UUID messageId, UUID paymentId) {
        Outcome claimed = tx.execute(s -> {
            if (!payments.markProcessed(messageId, TOPIC)) return Outcome.DUPLICATE;
            return payments.transition(paymentId, PaymentStatus.SUCCEEDED, PaymentStatus.REFUND_PENDING, null, null)
                    ? Outcome.REFUND_PENDING : Outcome.IGNORED;
        });
        if (claimed != Outcome.REFUND_PENDING) {
            if (claimed == Outcome.IGNORED) {
                log.info("Refund requested for payment {} which is not SUCCEEDED (unknown, failed or already refunding): ignored", paymentId);
            }
            return claimed;
        }
        return refundClaimed(payments.findById(paymentId).orElseThrow());
    }

    /**
     * For a payment this caller moved to REFUND_PENDING (or the reconciler's retry of one): ask the provider with
     * the stable key refund-<paymentId>, outside any transaction, then REFUND_PENDING -> REFUNDED.
     */
    public Outcome refundClaimed(Payment payment) {
        return switch (provider.refund(payment.id(), payment.providerRef())) {
            case RefundOutcome.Refunded refunded -> {
                payments.transition(payment.id(), PaymentStatus.REFUND_PENDING, PaymentStatus.REFUNDED, null, null);
                yield Outcome.REFUNDED;
            }
            case RefundOutcome.Failed failed -> {
                log.warn("Refund of payment {} failed ({}): left REFUND_PENDING for the reconciler", payment.id(), failed.reason());
                yield Outcome.REFUND_PENDING;
            }
        };
    }
}
