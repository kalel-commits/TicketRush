package com.ticketrush.gateway;

import com.ticketrush.common.security.ServiceTokens;
import com.ticketrush.common.support.TestJwts;
import com.ticketrush.common.support.TestKeys;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

import static com.ticketrush.common.support.TestJwts.validUserClaims;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "eureka.client.enabled=false",
        // deterministic limiter settings: requestedTokens makes bursts exact and refill ~1 request/min
        "ticketrush.ratelimit.strict.replenish-rate=1", "ticketrush.ratelimit.strict.burst-capacity=300", "ticketrush.ratelimit.strict.requested-tokens=60",
        "ticketrush.ratelimit.default.replenish-rate=1", "ticketrush.ratelimit.default.burst-capacity=180", "ticketrush.ratelimit.default.requested-tokens=60",
        "ticketrush.ratelimit.assistant.replenish-rate=1", "ticketrush.ratelimit.assistant.burst-capacity=120", "ticketrush.ratelimit.assistant.requested-tokens=60"})
class GatewayIT {

    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static final MockWebServer DOWNSTREAM = new MockWebServer();
    static final TestKeys KEYS = TestKeys.rsa();
    static final Path PUB;

    static {
        REDIS.start();
        DOWNSTREAM.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body("{}").build();
            }
        });
        try {
            DOWNSTREAM.start();
            PUB = Files.writeString(Files.createTempDirectory("gw-keys").resolve("pub.pem"), KEYS.publicPem());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        String mock = "http://localhost:" + DOWNSTREAM.getPort();
        for (String svc : List.of("user", "event", "booking", "payment", "assistant")) {
            r.add("ticketrush.routes." + svc + "-uri", () -> mock);
        }
        r.add("ticketrush.jwt.public-key", () -> "file:" + PUB.toAbsolutePath());
    }

    @AfterAll
    static void stop() {
        DOWNSTREAM.close();
    }

    @LocalServerPort
    int port;
    @Autowired
    ApplicationContext context;
    @Autowired
    Environment env;

    WebTestClient client;

    @BeforeEach
    void setUp() throws Exception {
        REDIS.execInContainer("redis-cli", "FLUSHALL");
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    static String userToken(String sub, String role) {
        return TestJwts.rs256(KEYS, validUserClaims(sub, role));
    }

    static String bearer(String token) {
        return "Bearer " + token;
    }

    List<Integer> post(String path, String token, int times) {
        return IntStream.range(0, times).mapToObj(i -> client.post().uri(path).header(AUTHORIZATION, bearer(token))
                .exchange().returnResult(Void.class).getStatus().value()).toList();
    }

    @Test
    void publicReadsNeedNoToken() {
        client.get().uri("/api/events").exchange().expectStatus().isOk();
        client.get().uri("/api/events/1/seats").exchange().expectStatus().isOk();
    }

    @Test
    void eventWritesRequireAdmin() {
        client.post().uri("/api/events").exchange().expectStatus().isUnauthorized();
        client.post().uri("/api/events").header(AUTHORIZATION, bearer(userToken("7", "USER"))).exchange().expectStatus().isForbidden();
        client.delete().uri("/api/events/1").header(AUTHORIZATION, bearer(userToken("7", "USER"))).exchange().expectStatus().isForbidden();
        client.post().uri("/api/events").header(AUTHORIZATION, bearer(userToken("1", "ADMIN"))).exchange().expectStatus().isOk();
    }

    @Test
    void bookingsPaymentsAndMeRequireAuthentication() {
        for (String path : List.of("/api/bookings", "/api/payments", "/api/users/me")) {
            client.get().uri(path).exchange().expectStatus().isUnauthorized();
        }
        client.get().uri("/api/bookings/me").header(AUTHORIZATION, bearer(userToken("7", "USER"))).exchange().expectStatus().isOk();
    }

    @Test
    void assistantRequiresAuthAndAdminForReindex() {
        client.post().uri("/api/assistant/chat").exchange().expectStatus().isUnauthorized();
        client.post().uri("/api/assistant/chat").header(AUTHORIZATION, bearer(userToken("7", "USER"))).exchange().expectStatus().isOk();
        client.post().uri("/api/assistant/admin/reindex").header(AUTHORIZATION, bearer(userToken("7", "USER"))).exchange().expectStatus().isForbidden();
        client.post().uri("/api/assistant/admin/reindex").header(AUTHORIZATION, bearer(userToken("1", "ADMIN"))).exchange().expectStatus().isOk();
    }

    @Test
    void assistantLimiterIsSeparateAndExact() {
        assertThat(post("/api/assistant/chat", userToken("7", "USER"), 3)).containsExactly(200, 200, 429);
        assertThat(post("/api/bookings", userToken("7", "USER"), 1)).containsOnly(200);
    }

    @Test
    void registerAndLoginArePublic() {
        client.post().uri("/api/users/register").exchange().expectStatus().isOk();
        client.post().uri("/api/users/login").exchange().expectStatus().isOk();
    }

    @Test
    void forgedAndWrongKindTokensAre401Not500() {
        List<String> bad = List.of(
                "garbage",
                TestJwts.rs256(KEYS, validUserClaims("1", "USER").issuedAt(Instant.now().minusSeconds(700)).expiresAt(Instant.now().minusSeconds(120))),
                TestJwts.rs256(TestKeys.rsa(), validUserClaims("1", "ADMIN")),
                TestJwts.unsigned(validUserClaims("1", "ADMIN")),
                TestJwts.hs256("x".repeat(32), validUserClaims("1", "ADMIN")),
                ServiceTokens.provider("s".repeat(32), "booking-service", Clock.systemUTC()).token(),
                TestJwts.rs256(KEYS, validUserClaims("1", "USER").audience(List.of("other"))));
        for (String t : bad) {
            client.get().uri("/api/bookings/me").header(AUTHORIZATION, bearer(t)).exchange().expectStatus().isUnauthorized();
        }
    }

    @Test
    void internalIsDeniedForEveryone() {
        int before = DOWNSTREAM.getRequestCount();
        client.get().uri("/internal/bookings/status").exchange().expectStatus().isUnauthorized();
        client.get().uri("/internal/bookings/status").header(AUTHORIZATION, bearer(userToken("1", "ADMIN")))
                .exchange().expectStatus().isForbidden();
        assertThat(DOWNSTREAM.getRequestCount()).isEqualTo(before);
    }

    @Test
    void unknownPathsAreDenied() {
        client.get().uri("/foo").header(AUTHORIZATION, bearer(userToken("7", "USER"))).exchange().expectStatus().isForbidden();
    }

    @Test
    void strictLimiterAllowsExactlyFiveThen429() {
        List<Integer> codes = post("/api/bookings", userToken("7", "USER"), 8);
        assertThat(codes.subList(0, 5)).containsOnly(200);
        assertThat(codes.subList(5, 8)).containsOnly(429);
    }

    @Test
    void strictAndDefaultBucketsAreSeparate() {
        post("/api/bookings", userToken("7", "USER"), 6);
        client.get().uri("/api/bookings/me").header(AUTHORIZATION, bearer(userToken("7", "USER"))).exchange().expectStatus().isOk();
    }

    @Test
    void limitsArePerUser() {
        post("/api/bookings", userToken("7", "USER"), 6);
        assertThat(post("/api/bookings", userToken("8", "USER"), 1)).containsOnly(200);
    }

    @Test
    void spoofedForwardedForDoesNotResetAnonymousBucket() {
        List<Integer> codes = IntStream.range(0, 5).mapToObj(i -> client.get().uri("/api/events")
                .header("X-Forwarded-For", "203.0.113." + i).exchange().returnResult(Void.class).getStatus().value()).toList();
        assertThat(codes).containsExactly(200, 200, 200, 429, 429);
    }

    @Test
    void unauthenticatedWritesDoNotConsumeTokens() {
        IntStream.range(0, 10).forEach(i -> client.post().uri("/api/bookings").exchange().expectStatus().isUnauthorized());
        assertThat(post("/api/bookings", userToken("9", "USER"), 1)).containsOnly(200);
    }

    @Test
    void corsPreflightAllowsFrontend() {
        client.options().uri("/api/bookings").header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "authorization,idempotency-key")
                .exchange().expectHeader().valueEquals("Access-Control-Allow-Origin", "http://localhost:5173");
    }

    @Test
    void corsExposesRetryAfterSoTheFrontendCanHonourA429() {
        client.get().uri("/api/events").header("Origin", "http://localhost:5173").exchange()
                .expectHeader().valueMatches("Access-Control-Expose-Headers", ".*Retry-After.*");
    }

    @Test
    void gatewayCannotSign() {
        assertThat(context.getBeanNamesForType(JwtEncoder.class)).isEmpty();
        assertThat(env.getProperty("INTERNAL_JWT_SECRET")).isNull();
    }
}
