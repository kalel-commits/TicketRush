package com.ticketrush.provider;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** In-memory charges and refunds, idempotent per key: concurrent requests with one key see one result. */
@Component
public class ChargeStore {

    public enum Status { SUCCEEDED, DECLINED }

    public record Charge(String chargeId, String idempotencyKey, long amountCents, String reference,
                         Status status, boolean refunded, Instant createdAt) {
        Charge asRefunded() {
            return new Charge(chargeId, idempotencyKey, amountCents, reference, status, true, createdAt);
        }
    }

    public record Refund(String refundId, String chargeId, String status) {}

    private final Map<String, Charge> chargesByKey = new ConcurrentHashMap<>();
    private final Map<String, String> keyByChargeId = new ConcurrentHashMap<>();
    private final Map<String, Refund> refundsByKey = new ConcurrentHashMap<>();

    /** Returns the stored charge for this key, creating it once with the given outcome. */
    public Charge charge(String key, long amountCents, String reference, Supplier<Status> outcome) {
        return chargesByKey.computeIfAbsent(key, k -> {
            Charge c = new Charge("ch_" + UUID.randomUUID(), k, amountCents, reference, outcome.get(), false, Instant.now());
            keyByChargeId.put(c.chargeId(), k);
            return c;
        });
    }

    public Optional<Charge> byKey(String key) {
        return Optional.ofNullable(chargesByKey.get(key));
    }

    public Collection<Charge> all() {
        return List.copyOf(chargesByKey.values());
    }

    /**
     * Idempotent per refund key. 404 for an unknown charge; 409 for a charge that took no money, a charge already
     * refunded under another key, or a key reused for a different charge.
     */
    public Refund refund(String key, String chargeId) {
        Refund refund = refundsByKey.computeIfAbsent(key, k -> {
            String chargeKey = keyByChargeId.get(chargeId);
            if (chargeKey == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown charge");
            Charge updated = chargesByKey.computeIfPresent(chargeKey, (ck, c) -> {
                if (c.status() != Status.SUCCEEDED) throw new ResponseStatusException(HttpStatus.CONFLICT, "Charge was not successful");
                if (c.refunded()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Charge already refunded");
                return c.asRefunded();
            });
            return new Refund("re_" + UUID.randomUUID(), updated.chargeId(), "REFUNDED");
        });
        if (!refund.chargeId().equals(chargeId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency-Key already used for another charge");
        }
        return refund;
    }

    /** Test support. */
    public void clear() {
        chargesByKey.clear();
        keyByChargeId.clear();
        refundsByKey.clear();
    }
}
