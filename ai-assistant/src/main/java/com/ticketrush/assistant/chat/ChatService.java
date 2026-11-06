package com.ticketrush.assistant.chat;

import com.ticketrush.assistant.chat.AssistantReply.Source;
import com.ticketrush.assistant.chat.AssistantReply.SuggestedAction;
import com.ticketrush.assistant.chat.AssistantReply.Usage;
import com.ticketrush.assistant.config.AiConfig;
import com.ticketrush.assistant.config.AiProperties;
import com.ticketrush.common.error.ApiException;
import com.ticketrush.common.error.ErrorCode;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Spec §24.4 chat pipeline: retrieve (top-k, threshold) -> short-circuit when nothing relevant and no context ids
 * (no model call, no cost) -> prompt = static system prompt, history, then context-as-data + question -> Claude with
 * the read-only tools. Spring AI 2.0's AnthropicChatModel does not execute tools itself, so this class runs the
 * tool loop (max 3 rounds) with the caller's token in the ToolContext (never in the prompt).
 */
@Service
public class ChatService {

    public static final String NOT_FOUND_ANSWER =
            "Sorry, I couldn't find anything about that in the TicketRush help articles. I can help with events, bookings, "
                    + "payments, refunds and your account; for anything else please contact support@ticketrush.example.";
    public static final String TOOL_LIMIT_ANSWER = "Sorry, I couldn't complete that lookup. Please try again.";
    public static final String TOOL_CONTEXT_AUTHORIZATION = "authorization";
    static final int MAX_TOOL_ROUNDS = 3;

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ChatService.class);

    /** The browser went away mid-stream: not a Claude failure, nothing left to send. */
    static final class ClientGone extends RuntimeException {
        ClientGone(Throwable cause) {
            super(cause.toString(), cause, false, false);
        }
    }

    private final ChatModel model;
    private final VectorStore vectorStore;
    private final ToolCallingManager toolCallingManager;
    private final List<ToolCallback> tools;
    private final AiProperties properties;
    private final MeterRegistry meters;
    private final String systemPrompt;
    private final CircuitBreaker claude;

    ChatService(ChatModel model, VectorStore vectorStore, ToolCallingManager toolCallingManager, List<ToolCallback> assistantTools,
                AiProperties properties, MeterRegistry meters, CircuitBreakerRegistry breakers,
                @Value("classpath:prompts/system-prompt.st") Resource systemPrompt) {
        this.model = model;
        // spec section 14 "ai-assistant -> Claude": 50 % failures over the last 10 calls (at least 5) opens it for 30 s
        this.claude = breakers.circuitBreaker("claude", CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED).slidingWindowSize(10)
                .minimumNumberOfCalls(5).failureRateThreshold(50).waitDurationInOpenState(Duration.ofSeconds(30)).build());
        this.vectorStore = vectorStore;
        this.toolCallingManager = toolCallingManager;
        this.tools = assistantTools;
        this.properties = properties;
        this.meters = meters;
        try {
            this.systemPrompt = systemPrompt.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** What the pipeline decided before calling the model; reused by the streaming endpoint. */
    record Prepared(Prompt prompt, List<Document> documents, boolean shortCircuit) {}

    /** No grounding, no answer: a vector-store failure is 503 without excerpts, and the model is never called. */
    public List<Document> retrieve(String question) {
        try {
            return vectorStore.similaritySearch(SearchRequest.builder().query(question)
                    .topK(properties.topK()).similarityThreshold(properties.similarityThreshold()).build());
        } catch (RuntimeException e) {
            log.warn("Retrieval failed (Qdrant): {}", e.toString());
            throw unavailable(List.of());
        }
    }

    /** 503 ASSISTANT_UNAVAILABLE with up to 3 retrieved excerpts, so the user still gets the relevant policy text. */
    static ApiException unavailable(List<Document> documents) {
        return new ApiException(ErrorCode.ASSISTANT_UNAVAILABLE, "The assistant is unavailable right now, please try again later")
                .withProperty("fallbackExcerpts", excerpts(documents));
    }

    /** Up to 3 retrieved chunks as {title, section, text}: shown when the model can't answer (spec §24.8). */
    static List<Map<String, Object>> excerpts(List<Document> documents) {
        return documents.stream().limit(3).map(d -> Map.<String, Object>of(
                "title", String.valueOf(d.getMetadata().get("title")), "section", String.valueOf(d.getMetadata().get("section")),
                "text", d.getText())).toList();
    }

    private ChatResponse callModel(Prompt prompt, List<Document> documents) {
        try {
            return claude.executeSupplier(() -> model.call(prompt));
        } catch (RuntimeException e) {   // includes CallNotPermittedException: an open circuit fails fast, no model call
            log.warn("Claude call failed: {}", e.toString());
            throw unavailable(documents);
        }
    }

    /**
     * A follow-up ("and how long is the hold?") means little alone: retrieve with the previous user turn too, so the
     * conversation's topic decides which articles are relevant.
     */
    static String retrievalQuery(ChatRequest request) {
        List<ChatRequest.HistoryItem> history = request.historyOrEmpty();
        for (int i = history.size() - 1; i >= 0; i--) {
            if ("user".equals(history.get(i).role())) return history.get(i).content() + " " + request.message();
        }
        return request.message();
    }

    Prepared prepare(ChatRequest request, String authorization) {
        List<Document> documents = retrieve(retrievalQuery(request));
        if (documents.isEmpty() && !request.hasContextIds()) {
            return new Prepared(null, documents, true);
        }
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));   // static: identical on every call, so it can be cached
        for (ChatRequest.HistoryItem h : request.historyOrEmpty()) {
            messages.add("user".equals(h.role()) ? new UserMessage(h.content()) : new AssistantMessage(h.content()));
        }
        messages.add(new UserMessage(contextBlock(documents) + idsBlock(request) + "<question>\n" + request.message() + "\n</question>"));
        AnthropicChatOptions options = AiConfig.chatOptions(properties, tools, Map.of(TOOL_CONTEXT_AUTHORIZATION, authorization));
        return new Prepared(new Prompt(messages, options), documents, false);
    }

    public AssistantReply chat(ChatRequest request, String authorization) {
        Prepared prepared = prepare(request, authorization);
        if (prepared.shortCircuit()) {
            return new AssistantReply(NOT_FOUND_ANSWER, false, List.of(), List.of(), List.of(), new Usage(0, 0));
        }
        Prompt prompt = prepared.prompt();
        ChatResponse response = callModel(prompt, prepared.documents());
        long in = 0, out = 0;
        LinkedHashSet<String> toolsUsed = new LinkedHashSet<>();
        List<ToolResult> toolResults = new ArrayList<>();
        int rounds = 0;
        while (true) {
            in += inputTokens(response);
            out += outputTokens(response);
            if (!response.hasToolCalls() || rounds == MAX_TOOL_ROUNDS) break;
            response = withToolArguments(response);
            response.getResult().getOutput().getToolCalls().forEach(c -> toolsUsed.add(c.name()));
            ToolExecutionResult executed;
            try {
                executed = toolCallingManager.executeToolCalls(prompt, response);
            } catch (RuntimeException e) {   // e.g. a tool name the model invented: never a 500
                log.warn("Tool execution failed: {}", e.toString());
                count(in, out);
                return new AssistantReply(TOOL_LIMIT_ANSWER, false, List.of(), List.copyOf(toolsUsed), List.of(), new Usage(in, out));
            }
            toolResults.addAll(ToolResult.from(response, executed));
            prompt = new Prompt(executed.conversationHistory(), prompt.getOptions());
            response = callModel(prompt, prepared.documents());
            rounds++;
        }
        count(in, out);
        String answer = response.hasToolCalls() ? TOOL_LIMIT_ANSWER : finalText(response);
        boolean refused = "refusal".equals(finishReason(response));
        return new AssistantReply(answer, !refused && !prepared.documents().isEmpty(), refused ? List.of() : sources(prepared.documents()),
                List.copyOf(toolsUsed), SuggestedActions.from(toolResults), new Usage(in, out));
    }

    /**
     * SSE (spec section 24.8): each model round is streamed as delta events; if a round ends with tool calls they are
     * run and the next round streams (max 3 rounds). Then one meta event and done. Any failure: an error event, then
     * the stream completes (never hangs).
     */
    public void stream(ChatRequest request, String authorization, SseEmitter emitter) {
        List<Document> retrieved = List.of();
        try {
            Prepared prepared = prepare(request, authorization);
            retrieved = prepared.documents();
            if (prepared.shortCircuit()) {
                send(emitter, "delta", Map.of("text", NOT_FOUND_ANSWER));
                send(emitter, "meta", Map.of("sources", List.of(), "suggestedActions", List.of(), "grounded", false));
                send(emitter, "done", Map.of());
                emitter.complete();
                return;
            }
            Prompt prompt = prepared.prompt();
            List<ToolResult> toolResults = new ArrayList<>();
            String finish = null;
            long streamedIn = 0, streamedOut = 0;
            for (int round = 0; ; round++) {
                if (!claude.tryAcquirePermission()) throw unavailable(prepared.documents());
                long started = System.nanoTime();
                StringBuilder text = new StringBuilder();
                List<AssistantMessage.ToolCall> calls = new ArrayList<>();
                Map<String, Object> toolTurnProperties = new java.util.HashMap<>();
                long[] usage = {0, 0};
                String[] finishReason = {finish};
                try {
                    // doOnNext handles each chunk as it arrives, so text already received is delivered even if the
                    // stream fails later (toIterable() could surface the error before draining buffered chunks)
                    model.stream(prompt).doOnNext(chunk -> {
                        if (chunk.getResult() == null) return;
                        AssistantMessage out = chunk.getResult().getOutput();
                        if (out.getText() != null && !out.getText().isEmpty()) {
                            text.append(out.getText());
                            send(emitter, "delta", Map.of("text", out.getText()));
                        }
                        if (out.hasToolCalls()) {
                            calls.addAll(out.getToolCalls());
                            toolTurnProperties.putAll(out.getMetadata());   // thinking blocks + signatures, sent back with the result
                        }
                        if (chunk.getMetadata() != null && chunk.getMetadata().getUsage() != null) {   // cumulative per round
                            usage[0] = Math.max(usage[0], inputTokens(chunk));
                            usage[1] = Math.max(usage[1], outputTokens(chunk));
                        }
                        String reason = chunk.getResult().getMetadata().getFinishReason();
                        if (reason != null) finishReason[0] = reason.toLowerCase();
                    }).blockLast(Duration.ofSeconds(90));
                    finish = finishReason[0];
                    claude.onSuccess(System.nanoTime() - started, TimeUnit.NANOSECONDS);
                    streamedIn += usage[0];
                    streamedOut += usage[1];
                } catch (ClientGone gone) {   // the user left: Claude was fine, don't count it against the breaker
                    claude.onSuccess(System.nanoTime() - started, TimeUnit.NANOSECONDS);
                    throw gone;
                } catch (RuntimeException e) {
                    claude.onError(System.nanoTime() - started, TimeUnit.NANOSECONDS, e);
                    throw e;
                }
                if (calls.isEmpty() || round == MAX_TOOL_ROUNDS) break;
                ChatResponse asked = withToolArguments(new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                        .content(text.toString()).properties(toolTurnProperties).toolCalls(calls).build()))));
                ToolExecutionResult executed;
                try {
                    executed = toolCallingManager.executeToolCalls(prompt, asked);
                } catch (RuntimeException e) {   // a tool the model invented: answer politely instead of failing
                    log.warn("Tool execution failed: {}", e.toString());
                    send(emitter, "delta", Map.of("text", TOOL_LIMIT_ANSWER));
                    finish = "tool_error";
                    break;
                }
                toolResults.addAll(ToolResult.from(asked, executed));
                prompt = new Prompt(executed.conversationHistory(), prompt.getOptions());
            }
            boolean refused = "refusal".equals(finish);
            if (refused) send(emitter, "delta", Map.of("text", AiConfig.REFUSAL_MESSAGE));
            if ("max_tokens".equals(finish)) send(emitter, "delta", Map.of("text", " (truncated)"));
            send(emitter, "meta", Map.of("sources", refused ? List.of() : sources(prepared.documents()),
                    "suggestedActions", SuggestedActions.from(toolResults), "grounded", !refused && !prepared.documents().isEmpty()));
            count(streamedIn, streamedOut);
            send(emitter, "done", Map.of());
            emitter.complete();
        } catch (ClientGone gone) {
            emitter.complete();   // nobody is listening any more
        } catch (RuntimeException e) {
            log.warn("Assistant stream failed: {}", e.toString());
            try {
                // same help text the /chat 503 carries, so the panel can still show the relevant policy
                send(emitter, "error", Map.of("code", ErrorCode.ASSISTANT_UNAVAILABLE.name(), "fallbackExcerpts", excerpts(retrieved)));
            } catch (RuntimeException clientGone) {
                // nothing left to tell
            }
            emitter.complete();
        }
    }

    private static void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {   // broken pipe / emitter already completed
            throw new ClientGone(e);
        }
    }

    /** Streamed no-input tools can arrive with "" arguments; tools expect a JSON object. Keeps message properties. */
    static ChatResponse withToolArguments(ChatResponse response) {
        AssistantMessage out = response.getResult().getOutput();
        if (out.getToolCalls().stream().noneMatch(c -> c.arguments() == null || c.arguments().isBlank())) return response;
        List<AssistantMessage.ToolCall> fixed = out.getToolCalls().stream().map(c -> c.arguments() == null || c.arguments().isBlank()
                ? new AssistantMessage.ToolCall(c.id(), c.type(), c.name(), "{}") : c).toList();
        AssistantMessage message = AssistantMessage.builder().content(out.getText()).properties(out.getMetadata()).toolCalls(fixed).build();
        return new ChatResponse(List.of(new Generation(message, response.getResult().getMetadata())), response.getMetadata());
    }

    static String finalText(ChatResponse response) {
        String reason = finishReason(response);
        if ("refusal".equals(reason)) return AiConfig.REFUSAL_MESSAGE;
        String text = response.getResult().getOutput().getText();
        text = text == null ? "" : text.strip();
        return "max_tokens".equals(reason) ? text + " (truncated)" : text;
    }

    static String finishReason(ChatResponse response) {
        String r = response.getResult() == null ? null : response.getResult().getMetadata().getFinishReason();
        return r == null ? null : r.toLowerCase();
    }

    static List<Source> sources(List<Document> documents) {
        return documents.stream().map(d -> new Source(String.valueOf(d.getMetadata().get("title")),
                String.valueOf(d.getMetadata().get("section")), String.valueOf(d.getMetadata().get("source")))).distinct().toList();
    }

    private static String contextBlock(List<Document> documents) {
        StringBuilder sb = new StringBuilder("<context>\n");
        for (Document d : documents) {
            sb.append("<document source=\"").append(d.getMetadata().get("source")).append("\">\n")
                    .append(d.getText()).append("\n</document>\n");
        }
        return sb.append("</context>\n").toString();
    }

    private static String idsBlock(ChatRequest request) {
        if (!request.hasContextIds()) return "";
        StringBuilder sb = new StringBuilder("<page>\n");
        if (request.context().eventId() != null) sb.append("The user is viewing event id ").append(request.context().eventId()).append(".\n");
        if (request.context().bookingId() != null) sb.append("The user is viewing booking id ").append(request.context().bookingId()).append(".\n");
        return sb.append("</page>\n").toString();
    }

    private static long inputTokens(ChatResponse r) {
        var u = r.getMetadata() == null ? null : r.getMetadata().getUsage();
        return u == null || u.getPromptTokens() == null ? 0 : u.getPromptTokens();
    }

    private static long outputTokens(ChatResponse r) {
        var u = r.getMetadata() == null ? null : r.getMetadata().getUsage();
        return u == null || u.getCompletionTokens() == null ? 0 : u.getCompletionTokens();
    }

    void count(long in, long out) {
        meters.counter("assistant.tokens", "type", "input").increment(in);
        meters.counter("assistant.tokens", "type", "output").increment(out);
    }

    /** One tool call and its JSON result, for suggested actions. */
    public record ToolResult(String name, String arguments, String result) {
        static List<ToolResult> from(ChatResponse request, ToolExecutionResult executed) {
            List<ToolResult> out = new ArrayList<>();
            var calls = request.getResult().getOutput().getToolCalls();
            var last = executed.conversationHistory().getLast();
            if (last instanceof org.springframework.ai.chat.messages.ToolResponseMessage trm) {
                for (var resp : trm.getResponses()) {
                    calls.stream().filter(c -> c.id().equals(resp.id())).findFirst()
                            .ifPresent(c -> out.add(new ToolResult(c.name(), c.arguments(), resp.responseData())));
                }
            }
            return out;
        }
    }
}
