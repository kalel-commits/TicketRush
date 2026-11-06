package com.ticketrush.assistant.tools;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/** Read-only event tools (spec §24.5). */
@Component
public class EventTools {

    static final List<String> EVENT_FIELDS =
            List.of("id", "name", "venue", "startsAt", "saleOpensAt", "saleClosesAt", "priceCents", "availableSeats");

    private final ReadOnlyServiceClient client;
    private final ObjectMapper json;

    EventTools(ReadOnlyServiceClient client, ObjectMapper json) {
        this.client = client;
        this.json = json;
    }

    @Tool(description = "Get one event: name, venue, start time, sale window, price in cents and available seats.")
    public String getEventDetails(@ToolParam(description = "the event id") long eventId, ToolContext context) {
        return ToolSupport.result(json, () -> ToolSupport.pick(json,
                client.get("event-service", "/api/events/{id}", Map.of("id", eventId), ToolSupport.token(context)), EVENT_FIELDS));
    }

    @Tool(description = "List up to 10 upcoming events with their id, name, start time, sale opening and available seats.")
    public String listUpcomingEvents(ToolContext context) {
        return ToolSupport.result(json, () -> ToolSupport.pickAll(json,
                client.get("event-service", "/api/events", Map.of(), ToolSupport.token(context)).get("content"),
                List.of("id", "name", "startsAt", "saleOpensAt", "availableSeats")));
    }
}
