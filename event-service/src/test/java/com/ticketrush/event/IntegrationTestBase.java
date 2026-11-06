package com.ticketrush.event;

import com.ticketrush.common.security.ServiceTokens;
import com.ticketrush.common.support.TestJwts;
import com.ticketrush.common.support.TestKeys;
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
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.ticketrush.common.support.TestJwts.validUserClaims;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** One MySQL + Redis + Kafka set shared by every IT, so all ITs reuse one Spring context. */
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "ticketrush.reconciler.seat-initial-delay-ms=3600000"})   // tests call reconcile() explicitly
@AutoConfigureMockMvc
abstract class IntegrationTestBase {

    static final String INTERNAL_SECRET = "event-test-internal-secret-0123456789";

    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0")
            .withDatabaseName("event_db").withUsername("event_svc").withPassword("event-test-pw")
            .withUrlParam("connectionTimeZone", "UTC");   // same time-zone mode as production
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.4.0");

    static final TestKeys KEYS = TestKeys.rsa();
    static final FakeBookingStatus BOOKINGS = new FakeBookingStatus();
    static final mockwebserver3.MockWebServer BOOKING_SERVER = new mockwebserver3.MockWebServer();
    static final Path PUB;

    static {
        MYSQL.start();
        REDIS.start();
        KAFKA.start();
        BOOKING_SERVER.setDispatcher(BOOKINGS);
        try {
            BOOKING_SERVER.start();
            PUB = Files.writeString(Files.createTempDirectory("ev-keys").resolve("pub.pem"), KEYS.publicPem());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("ticketrush.jwt.public-key", () -> "file:" + PUB.toAbsolutePath());
        r.add("ticketrush.internal-jwt-secret", () -> INTERNAL_SECRET);
        // the @LoadBalanced client resolves "booking-service" through the simple discovery client
        r.add("spring.cloud.discovery.client.simple.instances.booking-service[0].uri", () -> "http://localhost:" + BOOKING_SERVER.getPort());
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper json;

    static String admin() {
        return "Bearer " + TestJwts.rs256(KEYS, validUserClaims("1", "ADMIN"));
    }

    static String user() {
        return "Bearer " + TestJwts.rs256(KEYS, validUserClaims("7", "USER"));
    }

    static String service() {
        return "Bearer " + ServiceTokens.provider(INTERNAL_SECRET, "booking-service", Clock.systemUTC()).token();
    }

    /** A valid create-event body whose sale is open now. */
    static Map<String, Object> eventBody(int rows, int perRow) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("name", "Concert " + System.nanoTime());
        b.put("venue", "Arena");
        b.put("startsAt", now.plus(30, ChronoUnit.DAYS).toString());
        b.put("saleOpensAt", now.minus(1, ChronoUnit.HOURS).toString());
        b.put("priceCents", 2500);
        b.put("rows", rows);
        b.put("seatsPerRow", perRow);
        return b;
    }

    ResultActions createEvent(Map<String, Object> body, String auth) throws Exception {
        var req = post("/api/events").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        if (auth != null) req.header(AUTHORIZATION, auth);
        return mvc.perform(req);
    }

    long createEventId(int rows, int perRow) throws Exception {
        String body = createEvent(eventBody(rows, perRow), admin()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("id").asLong();
    }
}
