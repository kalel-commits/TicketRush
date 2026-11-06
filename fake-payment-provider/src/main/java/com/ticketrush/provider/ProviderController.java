package com.ticketrush.provider;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collection;
import java.util.concurrent.ThreadLocalRandom;

/** A tiny PSP: idempotent charges (key = caller's payment id), lookups, idempotent refunds (spec §10). */
@RestController
class ProviderController {

    record ChargeRequest(@Positive long amountCents, @NotBlank String reference) {}

    record RefundRequest(@NotBlank String chargeId) {}

    private final ChargeStore store;
    private final ProviderSettings settings;

    ProviderController(ChargeStore store, ProviderSettings settings) {
        this.store = store;
        this.settings = settings;
    }

    @PostMapping("/charges")
    ChargeStore.Charge charge(@RequestHeader("Idempotency-Key") String key, @Valid @RequestBody ChargeRequest req) {
        beforeRequest();
        ChargeStore.Charge charge = store.charge(key, req.amountCents(), req.reference(), () ->
                roll(settings.getDeclineRate()) ? ChargeStore.Status.DECLINED : ChargeStore.Status.SUCCEEDED);
        if (roll(settings.getTimeoutAfterChargeRate())) {
            sleep(settings.getTimeoutSleepMs());   // money taken, answer arrives after the client gave up
        }
        return charge;
    }

    @GetMapping(value = "/charges", params = "idempotencyKey")
    org.springframework.http.ResponseEntity<Object> lookup(@RequestParam String idempotencyKey) {
        beforeRequest();
        return store.byKey(idempotencyKey).<org.springframework.http.ResponseEntity<Object>>map(org.springframework.http.ResponseEntity::ok)
                // an explicit, machine-readable "no such charge": callers must not treat any 404 as this answer
                .orElseGet(() -> org.springframework.http.ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(java.util.Map.of("code", "CHARGE_NOT_FOUND")));
    }

    @GetMapping(value = "/charges", params = "!idempotencyKey")
    Collection<ChargeStore.Charge> list() {
        beforeRequest();
        return store.all();
    }

    @PostMapping("/refunds")
    ChargeStore.Refund refund(@RequestHeader("Idempotency-Key") String key, @Valid @RequestBody RefundRequest req) {
        beforeRequest();
        return store.refund(key, req.chargeId());
    }

    private void beforeRequest() {
        if (settings.isDown()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Provider down");
        sleep(settings.getLatencyMs());
    }

    private static boolean roll(double rate) {
        return rate > 0 && ThreadLocalRandom.current().nextDouble() < rate;
    }

    private static void sleep(long ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
