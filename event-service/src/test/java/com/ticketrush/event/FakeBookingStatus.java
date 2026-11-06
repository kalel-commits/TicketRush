package com.ticketrush.event;

import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.RecordedRequest;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/** booking-service's POST /internal/bookings/status (plan 3 contract): known ids only, unknown ids omitted. */
final class FakeBookingStatus extends Dispatcher {

    private final Map<UUID, String> statuses = new ConcurrentHashMap<>();
    final List<String> requestBodies = new CopyOnWriteArrayList<>();
    final List<String> authHeaders = new CopyOnWriteArrayList<>();
    volatile boolean failing;

    void reset() {
        statuses.clear();
        requestBodies.clear();
        authHeaders.clear();
        failing = false;
    }

    void put(UUID bookingId, String status) {
        statuses.put(bookingId, status);
    }

    @Override
    public MockResponse dispatch(RecordedRequest request) {
        String body = request.getBody().utf8();
        requestBodies.add(body);
        authHeaders.add(String.valueOf(request.getHeaders().get("Authorization")));
        if (failing) return json(503, "{\"code\":\"DEPENDENCY_UNAVAILABLE\"}");
        if (!"POST".equals(request.getMethod()) || !request.getUrl().encodedPath().equals("/internal/bookings/status")) {
            return json(404, "{}");
        }
        String answer = statuses.entrySet().stream()
                .filter(e -> body.contains(e.getKey().toString()))
                .map(e -> "{\"bookingId\":\"%s\",\"status\":\"%s\",\"paymentId\":null}".formatted(e.getKey(), e.getValue()))
                .collect(Collectors.joining(",", "[", "]"));
        return json(200, answer);
    }

    static MockResponse json(int code, String body) {
        return new MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build();
    }
}
