package com.ticketrush.assistant.tools;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read-only tools over the signed-in user's own bookings (spec §24.5). booking-service enforces ownership with the
 * relayed token, so another user's booking simply comes back as not_found.
 */
@Component
public class BookingTools {

    static final List<String> BOOKING_FIELDS =
            List.of("id", "eventId", "status", "seatNos", "amountCents", "expiresAt", "failureReason", "createdAt");

    private final ReadOnlyServiceClient client;
    private final ObjectMapper json;

    BookingTools(ReadOnlyServiceClient client, ObjectMapper json) {
        this.client = client;
        this.json = json;
    }

    @Tool(description = "List the signed-in user's 10 most recent bookings with id, event id, status, seats and hold expiry.")
    public String getMyBookings(ToolContext context) {
        return ToolSupport.result(json, () -> ToolSupport.pickAll(json,
                client.get("booking-service", "/api/bookings/me", Map.of(), ToolSupport.token(context)).get("content"),
                List.of("id", "eventId", "status", "seatNos", "expiresAt")));
    }

    @Tool(description = "Get one of the signed-in user's bookings by id: status, seats, amount, hold expiry and failure reason.")
    public String getBookingDetails(@ToolParam(description = "the booking id (a UUID)") String bookingId, ToolContext context) {
        UUID id;
        try {
            id = UUID.fromString(bookingId.strip());
        } catch (RuntimeException e) {
            return "{\"error\":\"invalid_id\"}";
        }
        return ToolSupport.result(json, () -> ToolSupport.pick(json,
                client.get("booking-service", "/api/bookings/{id}", Map.of("id", id), ToolSupport.token(context)), BOOKING_FIELDS));
    }
}
