package com.ticketrush.assistant.tools;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Spec §24.5: only GET, only the four path templates, only event/booking — enforced before any network I/O. */
class ReadOnlyServiceClientTest {

    // a RestClient that fails every request: the allow-list must reject before it is ever used
    final ReadOnlyServiceClient client = new ReadOnlyServiceClient(
            RestClient.builder().requestInterceptor((req, body, exec) -> { throw new AssertionError("no I/O expected"); }).build(),
            JsonMapper.builder().build(),
            io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry.ofDefaults());

    @Test
    void anythingOutsideTheAllowListIsRejectedBeforeIo() {
        for (String path : new String[]{"/internal/bookings/status", "/api/bookings", "/api/payments", "/api/events/{id}/seats", "/api/users/me"}) {
            assertThatThrownBy(() -> client.get("booking-service", path, Map.of(), "Bearer x"))
                    .as(path).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> client.get("payment-service", "/api/events", Map.of(), "Bearer x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.get("event-service", "/api/bookings/me", Map.of(), "Bearer x"))
                .as("path must belong to the named service").isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theOnlyPublicOperationIsGet() {
        assertThat(Arrays.stream(ReadOnlyServiceClient.class.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers())).map(Method::getName)).containsExactly("get");
    }
}
