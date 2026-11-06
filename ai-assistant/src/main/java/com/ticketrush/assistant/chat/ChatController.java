package com.ticketrush.assistant.chat;

import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Spec §24.8. The caller's own Authorization header is relayed to the read-only tools (never put in the prompt). */
@RestController
@RequestMapping("/api/assistant")
class ChatController {

    private final ChatService chat;

    ChatController(ChatService chat) {
        this.chat = chat;
    }

    @PostMapping("/chat")
    AssistantReply chat(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization, @Valid @RequestBody ChatRequest request) {
        return chat.chat(request, authorization);
    }

    /** text/event-stream: delta... then meta then done, or error (spec section 24.8). Generated on a virtual thread. */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization, @Valid @RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(90_000L);
        Thread.ofVirtual().name("assistant-stream").start(() -> chat.stream(request, authorization, emitter));
        return emitter;
    }
}
