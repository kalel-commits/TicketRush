package com.ticketrush.payment;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** Spec §16.1 proof 6 and the double-click case: never more than one provider charge per booking. */
class PaymentConcurrencyIT extends IntegrationTestBase {

    record Response(int status, JsonNode body) {}

    private List<Response> fire(long user, UUID booking, int n, boolean sameKey) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Response>> futures = new ArrayList<>();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < n; i++) {
                String key = sameKey ? "same-key" : "key-" + i;
                futures.add(pool.submit(() -> {
                    start.await();
                    var r = pay(user, key, booking).andReturn().getResponse();
                    return new Response(r.getStatus(), json.readTree(r.getContentAsString()));
                }));
            }
            start.countDown();
        }
        List<Response> out = new ArrayList<>();
        for (var f : futures) out.add(f.get());
        return out;
    }

    @Test
    void proof6_parallelAttemptsWithDifferentKeysChargeOnce() throws Exception {
        long u = uniqueUser();
        UUID b = pendingBooking(u);
        List<Response> responses = fire(u, b, 20, false);

        assertThat(PROVIDER.chargeCalls.get()).isEqualTo(1);
        assertThat(PROVIDER.charges()).isEqualTo(1);
        assertThat(activeRows(b)).isEqualTo(1);
        String winner = jdbc.queryForObject("SELECT BIN_TO_UUID(id) FROM payments WHERE booking_id=UUID_TO_BIN(?) AND status <> 'FAILED'",
                String.class, b.toString());
        assertThat(responses).filteredOn(r -> r.status() == 200).hasSize(1)
                .allMatch(r -> r.body().get("id").asString().equals(winner));
        assertThat(responses).filteredOn(r -> r.status() != 200).hasSize(19).allSatisfy(r -> {
            assertThat(r.status()).isEqualTo(409);
            assertThat(r.body().get("code").asString()).isEqualTo("PAYMENT_IN_PROGRESS_OR_DONE");
            assertThat(r.body().get("paymentId").asString()).isEqualTo(winner);
        });
    }

    @Test
    void doubleClickWithTheSameKeyCreatesOneAttempt() throws Exception {
        long u = uniqueUser();
        UUID b = pendingBooking(u);
        List<Response> responses = fire(u, b, 20, true);

        assertThat(paymentRows(b)).isEqualTo(1);
        assertThat(PROVIDER.chargeCalls.get()).isEqualTo(1);
        String id = jdbc.queryForObject("SELECT BIN_TO_UUID(id) FROM payments WHERE booking_id=UUID_TO_BIN(?)", String.class, b.toString());
        assertThat(responses).allSatisfy(r -> {
            assertThat(r.status()).isIn(200, 202);
            assertThat(r.body().get("id").asString()).isEqualTo(id);
        });
    }
}
