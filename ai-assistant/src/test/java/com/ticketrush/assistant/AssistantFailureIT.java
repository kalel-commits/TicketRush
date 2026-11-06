package com.ticketrush.assistant;

import com.ticketrush.assistant.config.AiConfig;
import com.ticketrush.assistant.kb.KnowledgeIngestionService;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Spec §24.9: the assistant degrades to honest, fast answers when Claude misbehaves. */
class AssistantFailureIT extends AssistantTestBase {

    @Autowired
    KnowledgeIngestionService ingestion;
    @Autowired
    CircuitBreakerRegistry breakers;

    @BeforeEach
    void setUp() {
        ingestion.ingest();
        breakers.circuitBreaker("claude").reset();
    }

    ResultActions ask(String message) throws Exception {
        return mvc.perform(post("/api/assistant/chat").header(AUTHORIZATION, userToken(7))
                .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"" + message + "\"}"));
    }

    @Test
    void modelFailureIs503WithTheRetrievedExcerpts() throws Exception {
        claude.willThrow(new RuntimeException("read timed out"));
        ask("Can I get a refund for a confirmed ticket?").andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ASSISTANT_UNAVAILABLE"))
                .andExpect(jsonPath("$.fallbackExcerpts[0].text").isNotEmpty())
                .andExpect(jsonPath("$.fallbackExcerpts.length()").value(org.hamcrest.Matchers.lessThanOrEqualTo(3)));
    }

    @Test
    void refusalBecomesTheFixedPoliteMessage() throws Exception {
        claude.willStopWith("", "refusal");
        ask("How many seats can I book?").andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value(AiConfig.REFUSAL_MESSAGE))
                .andExpect(jsonPath("$.grounded").value(false));
    }

    @Test
    void maxTokensMarksTheAnswerTruncated() throws Exception {
        claude.willStopWith("You can book up to four seats and", "max_tokens");
        ask("How many seats can I book?").andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("You can book up to four seats and (truncated)"));
    }

    @Test
    void repeatedFailuresOpenTheCircuitAndLaterCallsFailFast() throws Exception {
        claude.failAlways(new RuntimeException("overloaded"));
        for (int i = 0; i < 5; i++) ask("How many seats can I book?").andExpect(status().isServiceUnavailable());
        int callsBefore = claude.prompts().size();
        long start = System.nanoTime();

        ask("How many seats can I book?").andExpect(status().isServiceUnavailable());

        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(100 + 400);   // retrieval still runs; no model call
        assertThat(claude.prompts()).hasSize(callsBefore);
    }
}
