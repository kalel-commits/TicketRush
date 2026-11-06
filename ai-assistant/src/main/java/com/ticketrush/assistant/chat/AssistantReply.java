package com.ticketrush.assistant.chat;

import java.util.List;

/** Spec §24.8 response body. Named AssistantReply to avoid clashing with Spring AI's ChatResponse. */
public record AssistantReply(String answer, boolean grounded, List<Source> sources, List<String> toolsUsed,
                             List<SuggestedAction> suggestedActions, Usage usage) {

    public record Source(String title, String section, String source) {}

    public record SuggestedAction(String label, String href) {}

    public record Usage(long inputTokens, long outputTokens) {}
}
