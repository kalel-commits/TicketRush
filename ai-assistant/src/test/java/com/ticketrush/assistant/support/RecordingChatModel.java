package com.ticketrush.assistant.support;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Fake Claude for tests: records every prompt and answers from a script. Automated tests never call the real API.
 * Each call consumes the next scripted step; when the script is empty it answers "ok".
 */
public class RecordingChatModel implements ChatModel {

    sealed interface Step permits Reply, ToolCall, Fail, Stop {}

    record Reply(String text) implements Step {}

    record ToolCall(String name, String argumentsJson, Map<String, Object> properties) implements Step {}

    record Fail(RuntimeException error) implements Step {}

    record Stop(String text, String finishReason) implements Step {}

    private final List<Prompt> prompts = new CopyOnWriteArrayList<>();
    private final Deque<Step> script = new ArrayDeque<>();
    private volatile RuntimeException failAlways;
    private volatile RuntimeException failStreamAfterFirstChunk;

    public synchronized void reset() {
        prompts.clear();
        script.clear();
        failAlways = null;
        failStreamAfterFirstChunk = null;
    }

    public List<Prompt> prompts() {
        return List.copyOf(prompts);
    }

    public synchronized RecordingChatModel willReply(String text) {
        script.add(new Reply(text));
        return this;
    }

    /** The model asks to call a tool; the next scripted step answers after the tool result. */
    public synchronized RecordingChatModel willCallTool(String name, String argumentsJson) {
        script.add(new ToolCall(name, argumentsJson, Map.of()));
        return this;
    }

    /**
     * Like the real streaming AnthropicChatModel: the tool-call chunk carries the thinking blocks (signed) in the
     * message properties, which must be sent back with the tool result; no-input tools may arrive with "" arguments.
     */
    public synchronized RecordingChatModel willCallToolWithThinking(String name, String argumentsJson) {
        script.add(new ToolCall(name, argumentsJson, Map.of("anthropicThinkingContents", "thinking-block-with-signature")));
        return this;
    }

    public synchronized RecordingChatModel willThrow(RuntimeException e) {
        script.add(new Fail(e));
        return this;
    }

    public synchronized RecordingChatModel willStopWith(String text, String finishReason) {
        script.add(new Stop(text, finishReason));
        return this;
    }

    public void failAlways(RuntimeException e) {
        this.failAlways = e;
    }

    public void failStreamAfterFirstChunk(RuntimeException e) {
        this.failStreamAfterFirstChunk = e;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        prompts.add(prompt);
        if (failAlways != null) throw failAlways;
        Step step;
        synchronized (this) {
            step = script.isEmpty() ? new Reply("ok") : script.poll();
        }
        return switch (step) {
            case Reply r -> response(r.text(), "end_turn", List.of());
            case Stop s -> response(s.text(), s.finishReason(), List.of());
            case ToolCall t -> new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                    .properties(t.properties())
                    .toolCalls(List.of(new AssistantMessage.ToolCall("call-" + prompts.size(), "function", t.name(), t.argumentsJson())))
                    .build(), ChatGenerationMetadata.builder().finishReason("tool_use").build())),
                    ChatResponseMetadata.builder().usage(new DefaultUsage(120, 30)).model("fake-claude").build());
            case Fail f -> throw f.error();
        };
    }

    /** Streams the next reply word by word (optionally failing after the first chunk). */
    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        ChatResponse full = call(prompt);
        if (full.hasToolCalls()) return Flux.just(full);   // a tool request arrives as one chunk
        String text = full.getResult().getOutput().getText();
        String[] words = text.split("(?<= )");   // keeps the spaces, so the chunks join back to the text
        List<ChatResponse> chunks = new ArrayList<>();
        for (int i = 0; i < words.length; i++) {
            boolean last = i == words.length - 1;
            chunks.add(response(words[i], last ? full.getResult().getMetadata().getFinishReason() : null, List.of()));
        }
        RuntimeException failure = failStreamAfterFirstChunk;
        if (failure != null) {
            return Flux.concat(Flux.just(chunks.getFirst()), Flux.error(failure));
        }
        return Flux.fromIterable(chunks);
    }

    static ChatResponse response(String text, String finishReason, List<AssistantMessage.ToolCall> toolCalls) {
        AssistantMessage message = AssistantMessage.builder().content(text).toolCalls(toolCalls).build();
        ChatGenerationMetadata meta = ChatGenerationMetadata.builder().finishReason(finishReason).build();
        return new ChatResponse(List.of(new Generation(message, meta)),
                ChatResponseMetadata.builder().usage(new DefaultUsage(120, 30)).model("fake-claude").build());
    }
}
