package com.ticketrush.booking;

import com.ticketrush.common.security.ServiceTokens;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;

import static com.ticketrush.common.support.TestJwts.validUserClaims;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** MySQL + Redis + Kafka containers and a fake event-service shared by every IT (one Spring context). */
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "ticketrush.booking.sweeper-interval-ms=3600000",     // tests call sweep() explicitly
        "ticketrush.outbox.relay-interval-ms=3600000"})        // tests call publishBatch() explicitly
@AutoConfigureMockMvc
abstract class IntegrationTestBase {

    static final String INTERNAL_SECRET = "booking-test-internal-secret-0123456789";

    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0")
            .withDatabaseName("booking_db").withUsername("booking_svc").withPassword("booking-test-pw")
            .withUrlParam("connectionTimeZone", "UTC");
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.4.0");

    static final FakeEventService EVENTS = new FakeEventService();
    static final MockWebServer EVENT_SERVER = new MockWebServer();
    static final TestKeys KEYS = TestKeys.rsa();
    static final Path PUB;

    static {
        MYSQL.start();
        REDIS.start();
        KAFKA.start();
        EVENT_SERVER.setDispatcher(EVENTS);
        // payment-service (plan 4) owns payment.succeeded; create it up front so the listener subscribes immediately
        try (var admin = org.apache.kafka.clients.admin.AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new org.apache.kafka.clients.admin.NewTopic("payment.succeeded", 3, (short) 1))).all().get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        try {
            EVENT_SERVER.start();
            PUB = Files.writeString(Files.createTempDirectory("bk-keys").resolve("pub.pem"), KEYS.publicPem());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("ticketrush.jwt.public-key", () -> "file:" + PUB.toAbsolutePath());
        r.add("ticketrush.internal-jwt-secret", () -> INTERNAL_SECRET);
        // the @LoadBalanced client resolves "event-service" through the simple discovery client
        r.add("spring.cloud.discovery.client.simple.instances.event-service[0].uri", () -> "http://localhost:" + EVENT_SERVER.getPort());
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
    void resetFake() throws Exception {
        EVENTS.reset();
        breakers.getAllCircuitBreakers().forEach(io.github.resilience4j.circuitbreaker.CircuitBreaker::reset);   // no state leaks between tests
        REDIS.execInContainer("redis-cli", "FLUSHALL");
    }

    static String userToken(long userId) {
        return "Bearer " + TestJwts.rs256(KEYS, validUserClaims(String.valueOf(userId), "USER"));
    }

    static String serviceToken() {
        return "Bearer " + ServiceTokens.provider(INTERNAL_SECRET, "payment-service", Clock.systemUTC()).token();
    }

    static long uniqueUser() {
        return 1_000_000L + (long) (Math.random() * 1_000_000_000L);
    }

    static long uniqueEvent() {
        return 1L + (long) (Math.random() * 1_000_000_000L);
    }

    ResultActions book(long userId, String idemKey, long eventId, List<Long> seatIds) throws Exception {
        var req = post("/api/bookings").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("eventId", eventId, "seatIds", seatIds)))
                .header(AUTHORIZATION, userToken(userId));
        if (idemKey != null) req.header("Idempotency-Key", idemKey);
        return mvc.perform(req);
    }

    JsonNode bodyOf(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    ResultActions getBooking(long userId, String id) throws Exception {
        return mvc.perform(get("/api/bookings/" + id).header(AUTHORIZATION, userToken(userId)));
    }

    String bookingStatus(String bookingId) {
        return jdbc.queryForObject("SELECT status FROM bookings WHERE id=UUID_TO_BIN(?)", String.class, bookingId);
    }

    int quota(long userId, long eventId) {
        List<Integer> q = jdbc.queryForList("SELECT seats_reserved FROM user_event_quota WHERE user_id=? AND event_id=?",
                Integer.class, userId, eventId);
        return q.isEmpty() ? 0 : q.getFirst();
    }

    int outboxRows(String bookingId, String topic) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE aggregate_id=UUID_TO_BIN(?) AND topic=?",
                Integer.class, bookingId, topic);
    }
}
