package com.ticketrush.assistant.config;

import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingManager;
import com.ticketrush.assistant.tools.BookingTools;
import com.ticketrush.assistant.tools.EventTools;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/**
 * Model settings for Groq, called through its OpenAI-compatible API (base URL in application.yml): model + max
 * tokens, and no sampling parameters. The static system prompt stays first and unchanged, so a provider-side prefix
 * cache can reuse it without a cache option. Refusals and content-filter stops are answered client-side with
 * {@link #REFUSAL_MESSAGE}.
 */
@Configuration
public class AiConfig {

    public static final String REFUSAL_MESSAGE =
            "Sorry, I can't help with that. I can answer questions about TicketRush events, bookings, payments and policies.";

    public static OpenAiChatOptions chatOptions(AiProperties p) {
        return chatOptions(p, List.of(), Map.of());
    }

    /** Per-request options: the model settings plus the read-only tools and the caller's token as tool context. */
    public static OpenAiChatOptions chatOptions(AiProperties p, List<ToolCallback> tools, Map<String, Object> toolContext) {
        return OpenAiChatOptions.builder()
                .model(p.model())
                .maxTokens(p.maxTokens())
                // Groq rejects assistant turns carrying 'reasoning_content' (400 on the round after a tool call)
                .replayReasoningContent(false)
                .toolCallbacks(tools)
                .toolContext(toolContext)
                .build();
    }

    @Bean
    OpenAiChatOptions assistantChatOptions(AiProperties properties) {
        return chatOptions(properties);
    }

    /** The only tools the model can use: four GET-only lookups (spec §24.5). */
    @Bean
    List<ToolCallback> assistantTools(EventTools eventTools, BookingTools bookingTools) {
        return List.of(ToolCallbacks.from(eventTools, bookingTools));
    }

    /** Spring AI 2.0's OpenAiChatModel does not run tools itself; ChatService drives this loop (max 3 rounds). */
    @Bean
    ToolCallingManager toolCallingManager() {
        return DefaultToolCallingManager.builder().build();
    }
}
