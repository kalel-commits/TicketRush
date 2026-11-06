package com.ticketrush.payment;

import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.RecordedRequest;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Stands in for booking-service's GET /internal/bookings/{id} (plan 3 contract). */
final class FakeBookingService extends Dispatcher {

    record Booking(UUID id, long userId, long eventId, String status, long amountCents, Instant expiresAt) {}

    /** What POST /internal/bookings/status reports for a booking (overrides the stored booking's status). */
    record StatusView(String status, UUID paymentId) {}

    private final Map<UUID, Booking> bookings = new ConcurrentHashMap<>();
    private final Map<UUID, StatusView> statuses = new ConcurrentHashMap<>();
    final List<String> authHeaders = new CopyOnWriteArrayList<>();
    volatile boolean failing;

    void reset() {
        bookings.clear();
        statuses.clear();
        authHeaders.clear();
        failing = false;
    }

    void put(Booking b) {
        bookings.put(b.id(), b);
    }

    void status(UUID bookingId, String status, UUID paymentId) {
        statuses.put(bookingId, new StatusView(status, paymentId));
    }

    @Override
    public MockResponse dispatch(RecordedRequest request) {
        authHeaders.add(String.valueOf(request.getHeaders().get("Authorization")));
        if (failing) return json(503, "{\"code\":\"DEPENDENCY_UNAVAILABLE\"}");
        String path = request.getUrl().encodedPath();
        if ("POST".equals(request.getMethod()) && path.equals("/internal/bookings/status")) {
            String body = request.getBody().utf8();
            StringBuilder out = new StringBuilder("[");
            statuses.forEach((id, v) -> {
                if (!body.contains(id.toString())) return;   // unknown ids are omitted (plan 3 contract)
                if (out.length() > 1) out.append(',');
                out.append("{\"bookingId\":\"%s\",\"status\":\"%s\",\"paymentId\":%s}".formatted(id, v.status(),
                        v.paymentId() == null ? "null" : "\"" + v.paymentId() + "\""));
            });
            return json(200, out.append(']').toString());
        }
        if (!"GET".equals(request.getMethod()) || !path.startsWith("/internal/bookings/")) return json(404, "{\"code\":\"NOT_FOUND\"}");
        Booking b = bookings.get(UUID.fromString(path.substring("/internal/bookings/".length())));
        if (b == null) return json(404, "{\"code\":\"NOT_FOUND\"}");
        return json(200, """
                {"id":"%s","userId":%d,"eventId":%d,"status":"%s","amountCents":%d,"expiresAt":"%s","paymentId":null}"""
                .formatted(b.id(), b.userId(), b.eventId(), b.status(), b.amountCents(), b.expiresAt()));
    }

    static MockResponse json(int code, String body) {
        return new MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build();
    }
}
