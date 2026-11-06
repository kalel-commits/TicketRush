package com.ticketrush.assistant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Spec §24.5 read-only tools: token relay, GET only, trimmed output, errors as tool results (Review Focus 2). */
class ToolsIT extends AssistantTestBase {

    @Autowired
    @Qualifier("assistantTools")
    List<ToolCallback> tools;

    void services(int status, String body) {
        SERVICES.answer(status, body);
    }

    @AfterEach
    void quiet() {
        services(404, "{}");
    }

    ResultActions chatAbout(UUID booking, String token) throws Exception {
        return mvc.perform(post("/api/assistant/chat").header(AUTHORIZATION, token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"Is this booking confirmed?\",\"context\":{\"bookingId\":\"" + booking + "\"}}"));
    }

    /** What the tool handed back to the model (the tool response in the follow-up prompt). */
    String toolResultSeenByTheModel() {
        List<Message> second = claude.prompts().get(1).getInstructions();
        return ((ToolResponseMessage) second.getLast()).getResponses().getFirst().responseData();
    }

    @Test
    void registeredToolsAreExactlyTheFourReadOnlyOnes() {
        assertThat(tools).extracting(t -> t.getToolDefinition().name())
                .containsExactlyInAnyOrder("getEventDetails", "listUpcomingEvents", "getMyBookings", "getBookingDetails");
    }

    @Test
    void bookingLookupRelaysTheCallersTokenWithGetAndTrimsTheResult() throws Exception {
        UUID booking = UUID.randomUUID();
        services(200, """
                {"id":"%s","eventId":42,"status":"CONFIRMED","seatIds":[1],"seatNos":["A1"],"amountCents":2500,
                 "expiresAt":"2026-11-02T10:00:00Z","failureReason":null,"createdAt":"2026-11-02T09:50:00Z",
                 "email":"someone@example.com","providerRef":"ch_secret"}""".formatted(booking));
        claude.willCallTool("getBookingDetails", "{\"bookingId\":\"" + booking + "\"}").willReply("Yes, it is confirmed.");
        String token = userToken(7);

        chatAbout(booking, token).andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("Yes, it is confirmed."))
                .andExpect(jsonPath("$.toolsUsed[0]").value("getBookingDetails"))
                .andExpect(jsonPath("$.suggestedActions[0].href").value("/bookings/" + booking));

        assertThat(SERVICES.requests()).hasSize(1);
        assertThat(SERVICES.requests().getFirst().method()).isEqualTo("GET");
        assertThat(SERVICES.requests().getFirst().path()).isEqualTo("/api/bookings/" + booking);
        assertThat(SERVICES.requests().getFirst().authorization()).isEqualTo(token);
        String result = toolResultSeenByTheModel();
        assertThat(result).contains("CONFIRMED").contains("A1").doesNotContain("someone@example.com").doesNotContain("ch_secret");
        assertThat(claude.prompts().getFirst().getInstructions().toString()).doesNotContain(token.substring(7));   // never in the prompt
    }

    @Test
    void someoneElsesBookingIsNotFoundAndNothingLeaks() throws Exception {
        UUID notMine = UUID.randomUUID();
        services(404, "{\"code\":\"NOT_FOUND\"}");
        claude.willCallTool("getBookingDetails", "{\"bookingId\":\"" + notMine + "\"}").willReply("I couldn't find that booking.");

        chatAbout(notMine, userToken(7)).andExpect(status().isOk())
                .andExpect(jsonPath("$.suggestedActions").isEmpty());
        assertThat(toolResultSeenByTheModel()).isEqualTo("{\"error\":\"not_found\"}");
    }

    @Test
    void anUnavailableServiceBecomesAToolErrorNotAFailure() throws Exception {
        UUID booking = UUID.randomUUID();
        services(503, "{}");
        claude.willCallTool("getBookingDetails", "{\"bookingId\":\"" + booking + "\"}").willReply("Live booking data is unavailable right now.");

        chatAbout(booking, userToken(7)).andExpect(status().isOk());
        assertThat(toolResultSeenByTheModel()).isEqualTo("{\"error\":\"unavailable\"}");
        assertThat(SERVICES.requests()).isNotEmpty().allMatch(r -> r.method().equals("GET"));
    }

    @Test
    void aToolTheModelInventedIsAnsweredGracefully() throws Exception {
        claude.willCallTool("deleteAllBookings", "{}").willReply("unused");
        chatAbout(UUID.randomUUID(), userToken(7)).andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value(com.ticketrush.assistant.chat.ChatService.TOOL_LIMIT_ANSWER));
        assertThat(SERVICES.requests()).isEmpty();
    }

    @Test
    void noToolNoSuggestedActions() throws Exception {
        claude.willReply("Holds last 10 minutes.");
        mvc.perform(post("/api/assistant/chat").header(AUTHORIZATION, userToken(7)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"How long is a seat held?\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.suggestedActions").isEmpty()).andExpect(jsonPath("$.toolsUsed").isEmpty());
    }
}
