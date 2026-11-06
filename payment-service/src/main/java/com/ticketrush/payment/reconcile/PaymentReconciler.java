package com.ticketrush.payment.reconcile;

import com.ticketrush.payment.api.PaymentService;
import com.ticketrush.payment.domain.Payment;
import com.ticketrush.payment.domain.PaymentRepository;
import com.ticketrush.payment.domain.PaymentStatus;
import com.ticketrush.payment.messaging.RefundHandler;
import com.ticketrush.payment.outbox.OutboxWriter;
import com.ticketrush.payment.provider.ProviderClient;
import com.ticketrush.payment.provider.ProviderClient.LookupOutcome;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Spec §10 payment reconciler. (a) PROCESSING older than 15 s: ask the provider by idempotency key (= payment id).
 * (b) SUCCEEDED, unverified, older than 2 min: ask booking-service; refund if the booking did not end CONFIRMED by
 * this payment. (c) REFUND_PENDING older than 30 s: retry the refund with the same key.
 * No row lock is held across a remote call: every change is a conditional UPDATE, so the consumer, the pay
 * request and this job can race and exactly one of them wins each transition.
 */
@Component
public class PaymentReconciler {

    public record Report(int succeeded, int failed, int verified, int refundsRequested, int refunded) {}

    private static final Logger log = LoggerFactory.getLogger(PaymentReconciler.class);
    private static final int BATCH = 100;
    private static final Set<String> ENDED = Set.of("FAILED", "CANCELLED", "EXPIRED");

    private final PaymentRepository payments;
    private final ProviderClient provider;
    private final BookingStatusClient bookings;
    private final RefundHandler refunds;
    private final OutboxWriter outbox;
    private final TransactionTemplate tx;
    private final MeterRegistry meters;

    PaymentReconciler(PaymentRepository payments, ProviderClient provider, BookingStatusClient bookings, RefundHandler refunds,
                      OutboxWriter outbox, TransactionTemplate tx, MeterRegistry meters) {
        this.payments = payments;
        this.provider = provider;
        this.bookings = bookings;
        this.refunds = refunds;
        this.outbox = outbox;
        this.tx = tx;
        this.meters = meters;
    }

    @Scheduled(fixedDelayString = "${ticketrush.reconciler.payment-interval-ms:30000}",
            initialDelayString = "${ticketrush.reconciler.payment-initial-delay-ms:30000}")
    void scheduled() {
        try {
            Report r = reconcile();
            if (r.succeeded() + r.failed() + r.refundsRequested() + r.refunded() > 0) log.info("Payment reconciler: {}", r);
        } catch (RuntimeException e) {
            log.warn("Payment reconciler run failed, will retry: {}", e.toString());
        }
    }

    public Report reconcile() {
        int[] a = resolveProcessing();
        int[] b = verifySucceeded();
        int refunded = b[2] + retryRefunds();
        return new Report(a[0], a[1], b[0], b[1], refunded);
    }

    /** (a) @return {succeeded, failed} */
    private int[] resolveProcessing() {
        int succeeded = 0, failed = 0;
        for (Payment p : payments.findProcessingOlderThan(15, BATCH)) {
            switch (provider.lookup(p.id())) {
                case LookupOutcome.Charged charged -> {
                    boolean won = Boolean.TRUE.equals(tx.execute(s -> {
                        if (!payments.transition(p.id(), PaymentStatus.PROCESSING, PaymentStatus.SUCCEEDED, charged.chargeId(), null)) {
                            return false;
                        }
                        outbox.append(p.bookingId(), PaymentService.PAYMENT_SUCCEEDED, Map.of("paymentId", p.id().toString(),
                                "bookingId", p.bookingId().toString(), "amountCents", p.amountCents()));
                        return true;
                    }));
                    if (won) succeeded++;
                }
                case LookupOutcome.Declined declined -> {
                    if (payments.transition(p.id(), PaymentStatus.PROCESSING, PaymentStatus.FAILED, null, "DECLINED")) failed++;
                }
                case LookupOutcome.NotFound notFound -> {
                    if (payments.failIfNotFoundTwice(p.id())) failed++;
                    else payments.recordNotFound(p.id());
                }
                case LookupOutcome.Unavailable unavailable ->   // no answer is not "not found" (A7): change nothing
                        log.warn("Lookup of payment {} unavailable ({}): retry next cycle", p.id(), unavailable.reason());
            }
        }
        count("succeeded", succeeded);
        count("failed", failed);
        return new int[]{succeeded, failed};
    }

    /** (b) @return {verified, refundsRequested, refunded} */
    private int[] verifySucceeded() {
        List<Payment> unverified = payments.findUnverifiedSucceededOlderThan(120, BATCH);
        if (unverified.isEmpty()) return new int[]{0, 0, 0};
        if (!(bookings.statuses(unverified.stream().map(Payment::bookingId).distinct().toList())
                instanceof BookingStatusClient.StatusLookup.Found found)) {
            log.warn("Payment verification skipped for {} payments: booking-service unavailable", unverified.size());
            return new int[]{0, 0, 0};
        }
        int verified = 0, requested = 0, refunded = 0;
        for (Payment p : unverified) {
            BookingStatusClient.BookingState state = found.states().get(p.bookingId());
            boolean confirmedByThis = state != null && "CONFIRMED".equals(state.status()) && p.id().equals(state.paymentId());
            boolean mustRefund = state == null || ENDED.contains(state.status())
                    || "CONFIRMED".equals(state.status()) && !p.id().equals(state.paymentId());
            if (confirmedByThis) {
                if (payments.markVerified(p.id())) verified++;
            } else if (mustRefund) {
                // conditional: the Kafka refund path may claim the same payment; exactly one of us calls the provider
                if (payments.transition(p.id(), PaymentStatus.SUCCEEDED, PaymentStatus.REFUND_PENDING, null, null)) {
                    requested++;
                    if (refunds.refundClaimed(payments.findById(p.id()).orElseThrow()) == RefundHandler.Outcome.REFUNDED) refunded++;
                }
            }   // HOLDING / PENDING_PAYMENT: payment.succeeded is still on its way; wait
        }
        count("verified", verified);
        count("refund_requested", requested);
        count("refunded", refunded);
        return new int[]{verified, requested, refunded};
    }

    /** (c) @return refunded */
    private int retryRefunds() {
        int refunded = 0;
        for (Payment p : payments.findRefundPendingOlderThan(30, BATCH)) {
            if (refunds.refundClaimed(p) == RefundHandler.Outcome.REFUNDED) refunded++;
        }
        count("refunded", refunded);
        return refunded;
    }

    private void count(String action, int n) {
        if (n > 0) meters.counter("reconciler.payments", "action", action).increment(n);
    }
}
