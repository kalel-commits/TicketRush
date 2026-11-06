package com.ticketrush.payment;

import com.ticketrush.common.support.TestJwts;
import com.ticketrush.common.support.TestKeys;
import mockwebserver3.MockWebServer;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.ticketrush.common.support.TestJwts.validUserClaims;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** MySQL + Kafka containers, a fake booking-service and a fake provider, shared by every IT (one Spring context). */
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "ticketrush.outbox.relay-interval-ms=3600000",         // tests call publishBatch() explicitly
        "ticketrush.reconciler.payment-initial-delay-ms=3600000"})   // ...and reconcile()
@AutoConfigureMockMvc
abstract class IntegrationTestBase {

    static final String INTERNAL_SECRET = "payment-test-internal-secret-0123456789";
    static final long PRICE = 2500;

    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0")
            .withDatabaseName("payment_db").withUsername("payment_svc").withPassword("payment-test-pw")
            .withUrlParam("connectionTimeZone", "UTC");
    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.4.0");

    static final FakeBookingService BOOKINGS = new FakeBookingService();
    static final MockWebServer BOOKING_SERVER = new MockWebServer();
    static final FakeProvider PROVIDER = new FakeProvider();
    static final TestKeys KEYS = TestKeys.rsa();
    static final Path PUB;

    static {
        MYSQL.start();
        KAFKA.start();
        BOOKING_SERVER.setDispatcher(BOOKINGS);
        // booking-service (plan 3) owns payment.refund.requested; create it so the listener subscribes immediately
        try (var admin = org.apache.kafka.clients.admin.AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new org.apache.kafka.clients.admin.NewTopic("payment.refund.requested", 3, (short) 1))).all().get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        try {
            BOOKING_SERVER.start();
            PROVIDER.start();
            PUB = Files.writeString(Files.createTempDirectory("pay-keys").resolve("pub.pem"), KEYS.publicPem());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("ticketrush.jwt.public-key", () -> "file:" + PUB.toAbsolutePath());
        r.add("ticketrush.internal-jwt-secret", () -> INTERNAL_SECRET);
        r.add("ticketrush.provider.base-url", PROVIDER::url);
        // the @LoadBalanced client resolves "booking-service" through the simple discovery client
        r.add("spring.cloud.discovery.client.simple.instances.booking-service[0].uri", () -> "http://localhost:" + BOOKING_SERVER.getPort());
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper json;
    @Autowired
    io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry breakers;

    @BeforeEach
    void resetFakes() throws Exception {
        BOOKINGS.reset();
        PROVIDER.reset();
        breakers.getAllCircuitBreakers().forEach(io.github.resilience4j.circuitbreaker.CircuitBreaker::reset);   // no state leaks between tests
    }

    static String userToken(long userId) {
        return "Bearer " + TestJwts.rs256(KEYS, validUserClaims(String.valueOf(userId), "USER"));
    }

    static long uniqueUser() {
        return 1_000_000L + (long) (Math.random() * 1_000_000_000L);
    }

    /** A PENDING_PAYMENT booking owned by {@code userId}, expiring in 10 minutes. */
    static UUID pendingBooking(long userId) {
        return booking(userId, "PENDING_PAYMENT", Instant.now().plus(10, ChronoUnit.MINUTES));
    }

    static UUID booking(long userId, String status, Instant expiresAt) {
        UUID id = UUID.randomUUID();
        BOOKINGS.put(new FakeBookingService.Booking(id, userId, 7L, status, PRICE, expiresAt));
        return id;
    }

    ResultActions pay(long userId, String idemKey, UUID bookingId) throws Exception {
        var req = post("/api/payments").contentType(MediaType.APPLICATION_JSON)
                .content("{\"bookingId\":\"" + bookingId + "\"}")
                .header(AUTHORIZATION, userToken(userId));
        if (idemKey != null) req.header("Idempotency-Key", idemKey);
        return mvc.perform(req);
    }

    JsonNode bodyOf(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    String paymentStatus(String paymentId) {
        return jdbc.queryForObject("SELECT status FROM payments WHERE id=UUID_TO_BIN(?)", String.class, paymentId);
    }

    String failureReason(String paymentId) {
        return jdbc.queryForObject("SELECT failure_reason FROM payments WHERE id=UUID_TO_BIN(?)", String.class, paymentId);
    }

    int paymentRows(UUID bookingId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM payments WHERE booking_id=UUID_TO_BIN(?)", Integer.class, bookingId.toString());
    }

    int activeRows(UUID bookingId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM payments WHERE booking_id=UUID_TO_BIN(?) AND status <> 'FAILED'",
                Integer.class, bookingId.toString());
    }

    int outboxRows(UUID bookingId, String topic) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE aggregate_id=UUID_TO_BIN(?) AND topic=?",
                Integer.class, bookingId.toString(), topic);
    }
}
