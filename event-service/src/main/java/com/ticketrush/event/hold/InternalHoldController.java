package com.ticketrush.event.hold;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Service-token only (see SecurityConfig). Called by booking-service; never routed by the gateway. */
@RestController
class InternalHoldController {

    private final HoldService holds;

    InternalHoldController(HoldService holds) {
        this.holds = holds;
    }

    @PostMapping("/internal/events/{eventId}/holds")
    HoldResponse hold(@PathVariable long eventId, @Valid @RequestBody HoldRequest req) {
        return holds.hold(eventId, req);
    }
}
