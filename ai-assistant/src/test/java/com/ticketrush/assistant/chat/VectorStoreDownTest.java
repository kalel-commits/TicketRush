package com.ticketrush.assistant.chat;

import com.ticketrush.assistant.config.AiProperties;
import com.ticketrush.common.error.ApiException;
import com.ticketrush.common.error.ErrorCode;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Spec §24.9 / §17 "Qdrant down": no grounding means no answer. Chat fails with 503 ASSISTANT_UNAVAILABLE (no
 * excerpts, since retrieval itself failed) and the model is never called; health reports DOWN.
 */
class VectorStoreDownTest {

    @Test
    void chatIs503AndTheModelIsNeverCalled() {
        VectorStore store = mock(VectorStore.class);
        when(store.similaritySearch(any(SearchRequest.class))).thenThrow(new RuntimeException("UNAVAILABLE: io exception"));
        ChatModel model = mock(ChatModel.class);
        var service = new ChatService(model, store, DefaultToolCallingManager.builder().build(), List.of(),
                new AiProperties("claude-opus-5-5", 8192, "low", 4, 0.55, "kb-1", false), new SimpleMeterRegistry(),
                CircuitBreakerRegistry.ofDefaults(), new ClassPathResource("prompts/system-prompt.st"));

        assertThatThrownBy(() -> service.chat(new ChatRequest("How many seats can I book?", null, null), "Bearer x"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.ASSISTANT_UNAVAILABLE);
                    assertThat(e.properties().get("fallbackExcerpts")).isEqualTo(List.of());
                });
        verifyNoInteractions(model);
    }
}
