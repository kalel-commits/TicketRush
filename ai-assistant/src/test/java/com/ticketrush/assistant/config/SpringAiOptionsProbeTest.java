package com.ticketrush.assistant.config;

import com.anthropic.models.messages.OutputConfig;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicCacheStrategy;
import org.springframework.ai.anthropic.AnthropicChatOptions;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec §24.6 / U1: what Spring AI 2.0.2 lets us send to Claude. Effort and prompt caching are exposed and set;
 * no sampling parameters are sent; the beta "fallbacks" parameter is not exposed, so refusals are handled
 * client-side (ChatService maps stop_reason "refusal" to a fixed polite message).
 */
class SpringAiOptionsProbeTest {

    final AnthropicChatOptions options = AiConfig.chatOptions(new AiProperties("claude-opus-5-5", 8192, "low", 4, 0.55, "kb-1", true));

    @Test
    void modelAndCostCap() {
        assertThat(options.getModel()).isEqualTo("claude-opus-5-5");
        assertThat(options.getMaxTokens()).isEqualTo(8192);
    }

    @Test
    void noSamplingParametersAreSent() {
        assertThat(options.getTemperature()).isNull();
        assertThat(options.getTopP()).isNull();
        assertThat(options.getTopK()).isNull();
    }

    @Test
    void effortIsExposedAndSetToLow() {
        assertThat(options.getOutputConfig()).isNotNull();
        assertThat(options.getOutputConfig().effort()).contains(OutputConfig.Effort.LOW);
    }

    @Test
    void promptCachingCoversTheStaticSystemPromptAndTools() {
        assertThat(options.getCacheOptions().getStrategy()).isEqualTo(AnthropicCacheStrategy.SYSTEM_AND_TOOLS);
    }

    @Test
    void fallbacksAreNotExposedSoRefusalsAreHandledClientSide() {
        assertThat(java.util.Arrays.stream(AnthropicChatOptions.class.getMethods()).map(java.lang.reflect.Method::getName))
                .noneMatch(n -> n.toLowerCase().contains("fallback"));
        assertThat(AiConfig.REFUSAL_MESSAGE).isNotBlank();
    }
}
