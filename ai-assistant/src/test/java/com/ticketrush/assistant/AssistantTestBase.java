package com.ticketrush.assistant;

import com.ticketrush.assistant.support.RecordingChatModel;
import com.ticketrush.common.support.TestJwts;
import com.ticketrush.common.support.TestKeys;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.qdrant.QdrantContainer;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;

import static com.ticketrush.common.support.TestJwts.validUserClaims;

/** Real Qdrant (Testcontainers) + real ONNX embeddings + fake Claude. One Spring context for all ITs. */
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "spring.ai.model.chat=none",                  // no Anthropic client in tests: the RecordingChatModel answers
        "ticketrush.ai.ingest-on-startup=false"})     // tests ingest explicitly
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(AssistantTestBase.FakeClaude.class)   // nested configs of a superclass are not auto-detected
public abstract class AssistantTestBase {

    static final QdrantContainer QDRANT = new QdrantContainer("qdrant/qdrant:v1.18.0");
    static final TestKeys KEYS = TestKeys.rsa();
    /** Stands in for event-service and booking-service (both resolve to it through the simple discovery client). */
    protected static final com.ticketrush.assistant.support.FakeServices SERVICES = new com.ticketrush.assistant.support.FakeServices();
    static final Path PUB;

    static {
        QDRANT.start();
        try {
            PUB = Files.writeString(Files.createTempDirectory("ai-keys").resolve("pub.pem"), KEYS.publicPem());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("ticketrush.jwt.public-key", () -> "file:" + PUB.toAbsolutePath());
        r.add("spring.ai.vectorstore.qdrant.host", QDRANT::getHost);
        r.add("spring.cloud.discovery.client.simple.instances.event-service[0].uri", () -> "http://localhost:" + SERVICES.port());
        r.add("spring.cloud.discovery.client.simple.instances.booking-service[0].uri", () -> "http://localhost:" + SERVICES.port());
        r.add("spring.ai.vectorstore.qdrant.port", () -> QDRANT.getMappedPort(6334));
        // the ONNX model is downloaded once and reused across test runs
        r.add("spring.ai.embedding.transformer.cache.directory", () -> System.getProperty("user.home") + "/.ticketrush/onnx-cache");
    }

    @TestConfiguration
    static class FakeClaude {
        @Bean
        @Primary
        RecordingChatModel recordingChatModel() {
            return new RecordingChatModel();
        }
    }

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected ObjectMapper json;
    @Autowired
    protected RecordingChatModel claude;

    @Autowired
    protected io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry circuitBreakers;

    @BeforeEach
    void resetClaude() {
        claude.reset();
        circuitBreakers.getAllCircuitBreakers().forEach(io.github.resilience4j.circuitbreaker.CircuitBreaker::reset);   // no state leaks
    }

    protected static String userToken(long userId) {
        return "Bearer " + TestJwts.rs256(KEYS, validUserClaims(String.valueOf(userId), "USER"));
    }

    protected static String adminToken() {
        return "Bearer " + TestJwts.rs256(KEYS, validUserClaims("1", "ADMIN"));
    }
}
