package com.ticketrush.payment;

import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Mimics the fake-payment-provider contract (idempotent charges and refunds) with switchable failure modes. */
final class FakeProvider extends Dispatcher {

    enum Mode { APPROVE, DECLINE, TIMEOUT_AFTER_CHARGE, ERROR_500 }

    record Charge(String chargeId, String key, long amountCents, String reference, String status) {}

    private final JsonMapper json = JsonMapper.builder().build();
    private final Map<String, Charge> charges = new ConcurrentHashMap<>();
    private final Map<String, String> refunds = new ConcurrentHashMap<>();
    final AtomicInteger chargeCalls = new AtomicInteger();
    final AtomicInteger refundCalls = new AtomicInteger();
    final List<String> refundKeys = new CopyOnWriteArrayList<>();
    final List<String> refundedChargeIds = new CopyOnWriteArrayList<>();
    volatile Mode mode = Mode.APPROVE;
    volatile boolean refundsFail;
    volatile boolean lookupFails;

    private MockWebServer server = new MockWebServer();
    private boolean open;
    private int port;

    void start() throws Exception {
        server.setDispatcher(this);
        server.start();
        port = server.getPort();
        open = true;
    }

    String url() {
        return "http://localhost:" + port;
    }

    /** Close the port: connections are refused, i.e. the charge request is never sent. */
    synchronized void refuseConnections() throws Exception {
        server.close();
        open = false;
    }

    synchronized void reset() throws Exception {
        charges.clear();
        refunds.clear();
        refundKeys.clear();
        refundedChargeIds.clear();
        chargeCalls.set(0);
        refundCalls.set(0);
        mode = Mode.APPROVE;
        refundsFail = false;
        lookupFails = false;
        if (!open) {   // re-open on the same port after refuseConnections()
            server = new MockWebServer();
            server.setDispatcher(this);
            server.start(InetAddress.getByName("localhost"), port);
            open = true;
        }
    }

    int charges() {
        return charges.size();
    }

    Charge chargeFor(UUID paymentId) {
        return charges.get(paymentId.toString());
    }

    @Override
    public MockResponse dispatch(RecordedRequest request) {
        String path = request.getUrl().encodedPath();
        if ("POST".equals(request.getMethod()) && path.equals("/charges")) return charge(request);
        if ("POST".equals(request.getMethod()) && path.equals("/refunds")) return refund(request);
        if ("GET".equals(request.getMethod()) && path.equals("/charges")) return lookupOrList(request);
        return FakeBookingService.json(404, "{}");
    }

    /** A charge stored by tests directly, as if an earlier (lost) request had created it. */
    void storeCharge(UUID paymentId, long amountCents, String status) {
        charges.put(paymentId.toString(), new Charge("ch_" + UUID.randomUUID(), paymentId.toString(), amountCents, "test", status));
    }

    private MockResponse lookupOrList(RecordedRequest request) {
        if (lookupFails) return FakeBookingService.json(503, "{}");
        String key = request.getUrl().queryParameter("idempotencyKey");
        if (key != null) {
            Charge c = charges.get(key);
            return c == null ? FakeBookingService.json(404, "{\"code\":\"CHARGE_NOT_FOUND\"}") : FakeBookingService.json(200, toJson(c));
        }
        return FakeBookingService.json(200, charges.values().stream().map(this::toJson)
                .collect(java.util.stream.Collectors.joining(",", "[", "]")));
    }

    private String toJson(Charge c) {
        boolean refunded = refundedChargeIds.contains(c.chargeId());
        return """
                {"chargeId":"%s","idempotencyKey":"%s","amountCents":%d,"reference":"%s","status":"%s","refunded":%s}"""
                .formatted(c.chargeId(), c.key(), c.amountCents(), c.reference(), c.status(), refunded);
    }

    private MockResponse charge(RecordedRequest request) {
        chargeCalls.incrementAndGet();
        String key = request.getHeaders().get("Idempotency-Key");
        JsonNode body = json.readTree(request.getBody().utf8());
        Mode m = mode;
        if (m == Mode.ERROR_500) return FakeBookingService.json(500, "{}");
        Charge c = charges.computeIfAbsent(key, k -> new Charge("ch_" + UUID.randomUUID(), k, body.get("amountCents").asLong(),
                body.get("reference").asString(), m == Mode.DECLINE ? "DECLINED" : "SUCCEEDED"));
        String response = """
                {"chargeId":"%s","idempotencyKey":"%s","amountCents":%d,"reference":"%s","status":"%s","refunded":false}"""
                .formatted(c.chargeId(), c.key(), c.amountCents(), c.reference(), c.status());
        if (m == Mode.TIMEOUT_AFTER_CHARGE) {   // charged... but the answer comes after the client's 3 s read timeout
            return new MockResponse.Builder().code(200).headersDelay(4, TimeUnit.SECONDS)
                    .addHeader("Content-Type", "application/json").body(response).build();
        }
        return FakeBookingService.json(200, response);
    }

    private MockResponse refund(RecordedRequest request) {
        refundCalls.incrementAndGet();
        String key = request.getHeaders().get("Idempotency-Key");
        refundKeys.add(key);
        if (refundsFail) return FakeBookingService.json(500, "{}");
        String chargeId = json.readTree(request.getBody().utf8()).get("chargeId").asString();
        refundedChargeIds.add(chargeId);
        String refundId = refunds.computeIfAbsent(key, k -> "re_" + UUID.randomUUID());
        return FakeBookingService.json(200, "{\"refundId\":\"%s\",\"chargeId\":\"%s\",\"status\":\"REFUNDED\"}".formatted(refundId, chargeId));
    }
}
