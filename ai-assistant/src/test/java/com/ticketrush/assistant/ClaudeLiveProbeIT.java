package com.ticketrush.assistant;

import com.ticketrush.assistant.chat.AssistantReply;
import com.ticketrush.assistant.chat.ChatRequest;
import com.ticketrush.assistant.chat.ChatService;
import com.ticketrush.assistant.kb.KnowledgeIngestionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.testcontainers.qdrant.QdrantContainer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in probe against the REAL Claude API (plan 7 Task 1 Step 4): the configured options (model, effort, caching,
 * max tokens, no sampling parameters) are accepted, and the streaming path works end to end. Run with
 * ./mvnw -pl ai-assistant -am verify -Peval -Dit.test=ClaudeLiveProbeIT and ANTHROPIC_API_KEY set.
 */
@EnabledIfSystemProperty(named = "ticketrush.eval", matches = "true")
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
@SpringBootTest(properties = {"eureka.client.enabled=false", "ticketrush.ai.ingest-on-startup=false"})
class ClaudeLiveProbeIT {

    static final QdrantContainer QDRANT = new QdrantContainer("qdrant/qdrant:v1.18.0");
    static final Path PUB;

    static {
        QDRANT.start();
        try {
            PUB = Files.writeString(Files.createTempDirectory("probe-keys").resolve("pub.pem"),
                    com.ticketrush.common.support.TestKeys.rsa().publicPem());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("ticketrush.jwt.public-key", () -> "file:" + PUB.toAbsolutePath());
        r.add("spring.ai.vectorstore.qdrant.host", QDRANT::getHost);
        r.add("spring.ai.vectorstore.qdrant.port", () -> QDRANT.getMappedPort(6334));
        r.add("spring.ai.anthropic.api-key", () -> System.getenv("ANTHROPIC_API_KEY"));
        r.add("spring.ai.embedding.transformer.cache.directory", () -> System.getProperty("user.home") + "/.ticketrush/onnx-cache");
    }

    @Autowired
    ChatService chat;
    @Autowired
    KnowledgeIngestionService ingestion;

    @Test
    void realCallAndRealStreamSucceed() throws Exception {
        ingestion.ingest();
        AssistantReply reply = chat.chat(new ChatRequest("How many seats can I book for one event?", null, null), "Bearer probe");
        assertThat(reply.answer()).isNotBlank();
        assertThat(reply.usage().inputTokens()).isPositive();

        List<String> events = new CopyOnWriteArrayList<>();
        SseEmitter recorder = new SseEmitter(120_000L) {
            @Override
            public void send(SseEventBuilder builder) throws IOException {
                builder.build().forEach(d -> events.add(String.valueOf(d.getData())));
            }
        };
        chat.stream(new ChatRequest("How long is a seat held?", null, null), "Bearer probe", recorder);
        assertThat(String.join("", events)).doesNotContain("ASSISTANT_UNAVAILABLE");
        assertThat(events).anyMatch(e -> e.contains("event:done") || e.contains("done"));
    }
}
