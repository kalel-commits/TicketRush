package com.ticketrush.assistant.chat;

import com.ticketrush.assistant.chat.AssistantReply.SuggestedAction;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

/** Links derived from successful tool results only; model text is never parsed for links (spec §24.5). */
final class SuggestedActions {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private SuggestedActions() {
    }

    static List<SuggestedAction> from(List<ChatService.ToolResult> results) {
        List<SuggestedAction> out = new ArrayList<>();
        for (ChatService.ToolResult r : results) {
            JsonNode result;
            try {
                result = JSON.readTree(r.result());
            } catch (RuntimeException e) {
                continue;
            }
            if (result == null || result.has("error")) continue;
            SuggestedAction action = switch (r.name()) {
                case "getEventDetails" -> result.has("id") ? new SuggestedAction("Open event", "/events/" + result.get("id").asString()) : null;
                case "getBookingDetails" -> result.has("id") ? new SuggestedAction("Open booking", "/bookings/" + result.get("id").asString()) : null;
                case "getMyBookings" -> new SuggestedAction("My bookings", "/bookings");
                case "listUpcomingEvents" -> new SuggestedAction("Browse events", "/events");
                default -> null;
            };
            if (action != null && !out.contains(action)) out.add(action);
        }
        return out;
    }
}
