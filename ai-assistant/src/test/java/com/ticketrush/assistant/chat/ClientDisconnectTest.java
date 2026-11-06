package com.ticketrush.assistant.chat;

import com.ticketrush.assistant.config.AiProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.document.Document;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Review fix: users closing the chat mid-answer must not open the shared Claude circuit breaker for everyone. */
class ClientDisconnectTest {

    /** An emitter whose client has gone away: every send fails. */
    static class GoneEmitter extends SseEmitter {
        @Override
        public void send(SseEventBuilder builder) throws IOException {
            throw new IOException("Broken pipe");
        }
    }

    @Test
    void disconnectsAreNotCountedAsClaudeFailures() {
        VectorStore store = mock(VectorStore.class);
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(Document.builder().id("d1").text("Holds last 10 minutes.")
                .metadata(Map.of("title", "Booking policy", "section", "Holds", "source", "booking-policy.md")).build()));
        ChatModel model = mock(ChatModel.class);
        when(model.stream(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenAnswer(inv -> Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("Ten minutes."))))));
        CircuitBreakerRegistry breakers = CircuitBreakerRegistry.ofDefaults();
        var service = new ChatService(model, store, DefaultToolCallingManager.builder().build(), List.of(),
                new AiProperties("claude-opus-5-5", 8192, "low", 4, 0.35, "kb-1", false), new SimpleMeterRegistry(),
                breakers, new ClassPathResource("prompts/system-prompt.st"));

        for (int i = 0; i < 10; i++) {
            service.stream(new ChatRequest("How long is a hold?", null, null), "Bearer x", new GoneEmitter());
        }

        CircuitBreaker claude = breakers.circuitBreaker("claude");
        assertThat(claude.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(claude.getMetrics().getNumberOfFailedCalls()).isZero();
    }
}
