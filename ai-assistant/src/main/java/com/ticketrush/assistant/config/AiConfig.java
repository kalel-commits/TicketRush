package com.ticketrush.assistant.config;

import com.anthropic.models.messages.OutputConfig;
import org.springframework.ai.anthropic.AnthropicCacheOptions;
import org.springframework.ai.anthropic.AnthropicCacheStrategy;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingManager;
import com.ticketrush.assistant.tools.BookingTools;
import com.ticketrush.assistant.tools.EventTools;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/**
 * Claude settings, spec §24.6 (U1 resolved for Spring AI 2.0.2): model + 8,192 max tokens, effort "low",
 * prompt caching for the static system prompt and tools, and no sampling parameters. Spring AI does not expose the
 * beta "fallbacks" parameter, so refusals are answered client-side with {@link #REFUSAL_MESSAGE}.
 */
@Configuration
public class AiConfig {

    public static final String REFUSAL_MESSAGE =
            "Sorry, I can't help with that. I can answer questions about TicketRush events, bookings, payments and policies.";

    public static AnthropicChatOptions chatOptions(AiProperties p) {
        return chatOptions(p, List.of(), Map.of());
    }

    /** Per-request options: the spec settings plus the read-only tools and the caller's token as tool context. */
    public static AnthropicChatOptions chatOptions(AiProperties p, List<ToolCallback> tools, Map<String, Object> toolContext) {
        return AnthropicChatOptions.builder()
                .model(p.model())
                .maxTokens(p.maxTokens())
                .effort(OutputConfig.Effort.of(p.effort()))
                .cacheOptions(AnthropicCacheOptions.builder().strategy(AnthropicCacheStrategy.SYSTEM_AND_TOOLS).build())
                .toolCallbacks(tools)
                .toolContext(toolContext)
                .build();
    }

    @Bean
    AnthropicChatOptions assistantChatOptions(AiProperties properties) {
        return chatOptions(properties);
    }

    /** The only tools the model can use: four GET-only lookups (spec §24.5). */
    @Bean
    List<ToolCallback> assistantTools(EventTools eventTools, BookingTools bookingTools) {
        return List.of(ToolCallbacks.from(eventTools, bookingTools));
    }

    /** Spring AI 2.0's AnthropicChatModel does not run tools itself; ChatService drives this loop (max 3 rounds). */
    @Bean
    ToolCallingManager toolCallingManager() {
        return DefaultToolCallingManager.builder().build();
    }
}
