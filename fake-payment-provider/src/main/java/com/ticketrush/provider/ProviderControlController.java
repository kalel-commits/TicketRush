package com.ticketrush.provider;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Runtime behaviour switch for the live demo (plan 9). This is a test double on the internal Docker network only
 * (no published port, not routed by the gateway). Switching mode here, unlike restarting the container, keeps the
 * in-memory charges the payment reconciler needs to resolve an ambiguous charge. Not subject to {@code down}.
 */
@RestController
class ProviderControlController {

    record Behaviour(@DecimalMin("0.0") @DecimalMax("1.0") Double declineRate,
                     @DecimalMin("0.0") @DecimalMax("1.0") Double timeoutAfterChargeRate,
                     @PositiveOrZero Long timeoutSleepMs,
                     @PositiveOrZero Long latencyMs,
                     Boolean down) {}

    private final ProviderSettings settings;

    ProviderControlController(ProviderSettings settings) {
        this.settings = settings;
    }

    @GetMapping("/__control/behaviour")
    Behaviour current() {
        return new Behaviour(settings.getDeclineRate(), settings.getTimeoutAfterChargeRate(), settings.getTimeoutSleepMs(),
                settings.getLatencyMs(), settings.isDown());
    }

    /** Sets only the knobs present in the body. */
    @PostMapping("/__control/behaviour")
    Behaviour update(@Valid @RequestBody Behaviour b) {
        if (b.declineRate() != null) settings.setDeclineRate(b.declineRate());
        if (b.timeoutAfterChargeRate() != null) settings.setTimeoutAfterChargeRate(b.timeoutAfterChargeRate());
        if (b.timeoutSleepMs() != null) settings.setTimeoutSleepMs(b.timeoutSleepMs());
        if (b.latencyMs() != null) settings.setLatencyMs(b.latencyMs());
        if (b.down() != null) settings.setDown(b.down());
        return current();
    }
}
