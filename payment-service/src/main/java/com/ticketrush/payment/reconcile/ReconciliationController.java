package com.ticketrush.payment.reconcile;

import com.ticketrush.payment.domain.PaymentRepository;
import com.ticketrush.payment.domain.PaymentStatus;
import com.ticketrush.payment.provider.ProviderClient;
import com.ticketrush.payment.provider.ProviderClient.ProviderCharge;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Spec §10: compares every provider charge with the local attempt that made it (provider idempotency key =
 * payment id). Used by the load-test invariants (plan 6). Service token only (/internal/**).
 */
@RestController
class ReconciliationController {

    record Mismatch(String paymentId, String providerStatus, boolean providerRefunded, String localStatus) {}

    record Report(int providerCharges, long providerSucceeded, long providerRefunded,
                  int localSucceeded, int localRefundPending, int localRefunded, int localProcessing, List<Mismatch> mismatches) {}

    private final ProviderClient provider;
    private final PaymentRepository payments;

    ReconciliationController(ProviderClient provider, PaymentRepository payments) {
        this.provider = provider;
        this.payments = payments;
    }

    @GetMapping("/internal/payments/reconciliation-report")
    Report report() {
        List<ProviderCharge> charges = provider.listCharges();
        Map<UUID, PaymentStatus> local = payments.statusesOf(charges.stream().map(c -> UUID.fromString(c.idempotencyKey())).toList());
        List<Mismatch> mismatches = new ArrayList<>();
        for (ProviderCharge c : charges) {
            PaymentStatus status = local.get(UUID.fromString(c.idempotencyKey()));
            if (status == PaymentStatus.PROCESSING) continue;   // unresolved, reported in localProcessing, not a mismatch
            if (status == null || !expected(c).contains(status)) {
                mismatches.add(new Mismatch(c.idempotencyKey(), c.status(), c.refunded(), status == null ? "MISSING" : status.name()));
            }
        }
        Map<PaymentStatus, Integer> counts = payments.countByStatus();
        return new Report(charges.size(),
                charges.stream().filter(c -> "SUCCEEDED".equals(c.status())).count(),
                charges.stream().filter(ProviderCharge::refunded).count(),
                counts.getOrDefault(PaymentStatus.SUCCEEDED, 0), counts.getOrDefault(PaymentStatus.REFUND_PENDING, 0),
                counts.getOrDefault(PaymentStatus.REFUNDED, 0), counts.getOrDefault(PaymentStatus.PROCESSING, 0), mismatches);
    }

    /** What the local attempt must say for this provider charge. */
    private static Set<PaymentStatus> expected(ProviderCharge c) {
        if (!"SUCCEEDED".equals(c.status())) return Set.of(PaymentStatus.FAILED);   // declined: no money taken
        return c.refunded() ? Set.of(PaymentStatus.REFUNDED) : Set.of(PaymentStatus.SUCCEEDED, PaymentStatus.REFUND_PENDING);
    }
}
