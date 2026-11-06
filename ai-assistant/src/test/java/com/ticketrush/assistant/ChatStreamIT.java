package com.ticketrush.assistant;

import com.ticketrush.assistant.kb.KnowledgeIngestionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

/** Spec §24.8 SSE: delta... -> meta -> done; a failure mid-stream ends with an error event (Review Focus 4). */
class ChatStreamIT extends AssistantTestBase {

    @Autowired
    KnowledgeIngestionService ingestion;
    @Autowired
    io.micrometer.core.instrument.MeterRegistry meters;

    @BeforeEach
    void knowledgeBase() {
        ingestion.ingest();
    }

    /** Event names in order, from the SSE body. */
    List<String> events(String body) {
        List<String> names = new ArrayList<>();
        for (String line : body.split("\n")) {
            if (line.startsWith("event:")) names.add(line.substring(6).trim());
        }
        return names;
    }

    /** The concatenated "text" of every delta event. */
    String deltaText(String body) throws Exception {
        StringBuilder sb = new StringBuilder();
        String event = null;
        for (String line : body.split("\n")) {
            if (line.startsWith("event:")) event = line.substring(6).trim();
            else if (line.startsWith("data:") && "delta".equals(event)) sb.append(json.readTree(line.substring(5)).get("text").asString());
        }
        return sb.toString();
    }

    String stream(String message) throws Exception {
        MvcResult started = mvc.perform(post("/api/assistant/chat/stream").header(AUTHORIZATION, userToken(7))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"" + message + "\"}"))
                .andExpect(request().asyncStarted()).andReturn();
        started.getAsyncResult(5_000);
        return mvc.perform(asyncDispatch(started)).andReturn().getResponse().getContentAsString();
    }

    @Test
    void deltasThenOneMetaThenDone() throws Exception {
        claude.willReply("Seats are held for 10 minutes.");

        String body = stream("How long is a seat held?");

        List<String> names = events(body);
        assertThat(names.getLast()).isEqualTo("done");
        assertThat(names.get(names.size() - 2)).isEqualTo("meta");
        assertThat(names.subList(0, names.size() - 2)).isNotEmpty().allMatch("delta"::equals);
        assertThat(deltaText(body)).isEqualTo("Seats are held for 10 minutes.");   // deltas join back to the answer
        assertThat(body).contains("booking-policy.md").contains("\"grounded\":true");
    }

    /** Review fix: real Claude streams thinking blocks (to be sent back) and "" arguments for no-input tools. */
    @Test
    void streamedNoArgumentToolCallKeepsThinkingAndCompletes() throws Exception {
        SERVICES.answer(200, "{\"content\":[],\"page\":0,\"size\":10}");
        claude.willCallToolWithThinking("getMyBookings", "").willReply("You have no bookings yet.");
        double before = meters.counter("assistant.tokens", "type", "input").count();

        String body = stream("What are my bookings?");

        assertThat(events(body)).doesNotContain("error").last().isEqualTo("done");
        assertThat(deltaText(body)).isEqualTo("You have no bookings yet.");
        var followUp = claude.prompts().get(1).getInstructions();
        var assistantTurn = followUp.get(followUp.size() - 2);
        assertThat(assistantTurn.getMetadata()).containsEntry("anthropicThinkingContents", "thinking-block-with-signature");
        assertThat(meters.counter("assistant.tokens", "type", "input").count()).isGreaterThan(before);   // streaming records usage too
    }

    @Test
    void failureMidStreamEndsWithAnErrorEventInsteadOfHanging() throws Exception {
        claude.willReply("Seats are held for 10 minutes.");
        claude.failStreamAfterFirstChunk(new RuntimeException("connection reset by Claude"));
        long start = System.nanoTime();

        String body = stream("How long is a seat held?");

        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(2_000);
        assertThat(events(body)).containsExactly("delta", "error");
        assertThat(body).contains("ASSISTANT_UNAVAILABLE");
        // like the /chat 503: the retrieved help text still reaches the user
        assertThat(body).contains("fallbackExcerpts").contains("Booking policy");
    }
}
