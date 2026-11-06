package com.ticketrush.assistant;

import com.ticketrush.assistant.chat.AssistantReply;
import com.ticketrush.assistant.chat.ChatRequest;
import com.ticketrush.assistant.chat.ChatService;
import com.ticketrush.assistant.kb.KnowledgeIngestionService;
import com.ticketrush.common.error.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.qdrant.QdrantContainer;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in eval against the REAL Claude model (spec §24.10): ./mvnw -pl ai-assistant -am verify -Peval with
 * ANTHROPIC_API_KEY set. Never part of the normal build. Writes target/eval-report.json.
 * Personal items need tool access to running event/booking services and are reported, not gated, here.
 */
@EnabledIfSystemProperty(named = "ticketrush.eval", matches = "true")
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
@SpringBootTest(properties = {"eureka.client.enabled=false", "ticketrush.ai.ingest-on-startup=false"})
class GoldenEvalIT {

    static final QdrantContainer QDRANT = new QdrantContainer("qdrant/qdrant:v1.18.0");

    static final Path PUB;

    static {
        QDRANT.start();
        try {   // a throwaway key: the eval calls ChatService directly, no HTTP auth involved
            PUB = Files.writeString(Files.createTempDirectory("eval-keys").resolve("pub.pem"),
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
    @SuppressWarnings("unchecked")
    void goldenQuestions() throws Exception {
        ingestion.ingest();
        List<Map<String, Object>> items = new Yaml().load(new ClassPathResource("eval/golden.yaml").getInputStream());
        List<Map<String, Object>> report = new ArrayList<>();
        int gatedPass = 0, gated = 0, actionPass = 0, actions = 0;
        long in = 0, out = 0;
        for (Map<String, Object> item : items) {
            String type = (String) item.get("type");
            boolean ok;
            String answer;
            try {
                AssistantReply r = chat.chat(new ChatRequest((String) item.get("question"), null, null), "Bearer eval-no-user");
                answer = r.answer();
                in += r.usage().inputTokens();
                out += r.usage().outputTokens();
                ok = r.sources().stream().anyMatch(s -> s.source().equals(item.get("expectSource"))) || item.get("expectSource") == null;
                for (String must : (List<String>) item.getOrDefault("mustContain", List.of())) ok &= answer.contains(must);
                for (String mustNot : (List<String>) item.getOrDefault("mustNotContain", List.of())) ok &= !answer.toLowerCase().contains(mustNot.toLowerCase());
            } catch (ApiException e) {
                answer = "ERROR " + e.code();
                ok = false;
            }
            if ("action".equals(type)) { actions++; if (ok) actionPass++; }
            else if ("policy".equals(type)) { gated++; if (ok) gatedPass++; }
            report.add(Map.of("id", item.get("id"), "type", type, "pass", ok, "answer", answer));
        }
        Files.writeString(Path.of("target/eval-report.json"), new tools.jackson.databind.json.JsonMapper().writerWithDefaultPrettyPrinter()
                .writeValueAsString(Map.of("policyPass", gatedPass + "/" + gated, "actionPass", actionPass + "/" + actions,
                        "inputTokens", in, "outputTokens", out, "items", report)));
        assertThat(actionPass).as("action requests must never claim an action").isEqualTo(actions);
        assertThat(gatedPass).as("policy items").isGreaterThanOrEqualTo(gated - 2);
    }
}
