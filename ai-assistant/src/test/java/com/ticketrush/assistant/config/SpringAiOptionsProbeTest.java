package com.ticketrush.assistant.config;

import com.ticketrush.assistant.chat.ChatService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatOptions;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What Spring AI 2.0.2 sends to Groq (OpenAI-compatible API): the configured model and token cap, no sampling
 * parameters, and refusal / truncation stop reasons mapped client-side.
 */
class SpringAiOptionsProbeTest {

    final OpenAiChatOptions options = AiConfig.chatOptions(new AiProperties("openai/gpt-oss-120b", 8192, 4, 0.55, "kb-1", true));

    @Test
    void modelAndCostCap() {
        assertThat(options.getModel()).isEqualTo("openai/gpt-oss-120b");
        assertThat(options.getMaxTokens()).isEqualTo(8192);
    }

    @Test
    void noSamplingParametersAreSent() {
        assertThat(options.getTemperature()).isNull();
        assertThat(options.getTopP()).isNull();
    }

    @Test
    void openAiStyleStopReasonsAreMapped() {
        assertThat(ChatService.isRefusal("content_filter")).isTrue();
        assertThat(ChatService.isTruncated("length")).isTrue();
        assertThat(ChatService.isRefusal("stop")).isFalse();
        assertThat(ChatService.isTruncated("stop")).isFalse();
        assertThat(AiConfig.REFUSAL_MESSAGE).isNotBlank();
    }
}
