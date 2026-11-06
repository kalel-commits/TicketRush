package com.ticketrush.booking;

import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.RecordedRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stands in for event-service's POST /internal/events/{id}/holds with the same contract:
 * atomic seat ownership (like the MySQL conditional update), idempotent per bookingId, all-or-nothing.
 */
final class FakeEventService extends Dispatcher {

    enum Mode { NORMAL, REJECT_SEATS, SALE_NOT_OPEN, NOT_FOUND, FAIL_503, TIMEOUT_AFTER_COMMIT }

    static final long PRICE = 2500;

    private final JsonMapper json = JsonMapper.builder().build();
    private final Map<Long, String> owners = new HashMap<>();   // seatId -> bookingId
    final AtomicInteger holdCalls = new AtomicInteger();
    final List<String> authHeaders = new ArrayList<>();
    volatile Mode mode = Mode.NORMAL;

    synchronized void reset() {
        owners.clear();
        authHeaders.clear();
        holdCalls.set(0);
        mode = Mode.NORMAL;
    }

    synchronized String owner(long seatId) {
        return owners.get(seatId);
    }

    synchronized void release(String bookingId) {
        owners.values().removeIf(bookingId::equals);
    }

    @Override
    public MockResponse dispatch(RecordedRequest request) {
        String path = request.getUrl().encodedPath();
        if (!"POST".equals(request.getMethod()) || !path.matches("/internal/events/\\d+/holds")) {
            return json(404, "{\"code\":\"NOT_FOUND\"}");
        }
        holdCalls.incrementAndGet();
        synchronized (this) {
            authHeaders.add(request.getHeaders().get("Authorization"));
        }
        JsonNode body = json.readTree(request.getBody().utf8());
        String booking = body.get("bookingId").asString();
        List<Long> seats = new ArrayList<>();
        body.get("seatIds").forEach(n -> seats.add(n.asLong()));
        int holdSeconds = body.get("holdSeconds").asInt();
        return switch (mode) {
            case REJECT_SEATS -> json(409, "{\"code\":\"SEATS_UNAVAILABLE\"}");
            case SALE_NOT_OPEN -> json(409, "{\"code\":\"SALE_NOT_OPEN\"}");
            case NOT_FOUND -> json(404, "{\"code\":\"NOT_FOUND\"}");
            case FAIL_503 -> json(503, "{\"code\":\"DEPENDENCY_UNAVAILABLE\"}");
            case TIMEOUT_AFTER_COMMIT -> {
                hold(booking, seats);                                   // committed...
                yield new MockResponse.Builder().code(200).headersDelay(4, TimeUnit.SECONDS)   // ...but the answer is too late
                        .addHeader("Content-Type", "application/json").body(success(seats, holdSeconds)).build();
            }
            case NORMAL -> hold(booking, seats) ? json(200, success(seats, holdSeconds)) : json(409, "{\"code\":\"SEATS_UNAVAILABLE\"}");
        };
    }

    private synchronized boolean hold(String booking, List<Long> seats) {
        for (Long s : seats) {
            String o = owners.get(s);
            if (o != null && !o.equals(booking)) return false;
        }
        seats.forEach(s -> owners.put(s, booking));
        return true;
    }

    private String success(List<Long> seats, int holdSeconds) {
        List<Long> sorted = seats.stream().sorted().toList();
        String seatNos = String.join(",", sorted.stream().map(s -> "\"S" + s + "\"").toList());
        Instant until = Instant.now().truncatedTo(ChronoUnit.MILLIS).plusSeconds(holdSeconds);
        return "{\"heldUntil\":\"" + until + "\",\"amountCents\":" + PRICE * seats.size() + ",\"seatNos\":[" + seatNos + "]}";
    }

    private static MockResponse json(int code, String body) {
        return new MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build();
    }
}
