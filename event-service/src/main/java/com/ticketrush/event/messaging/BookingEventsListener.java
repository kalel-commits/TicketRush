package com.ticketrush.event.messaging;

import com.ticketrush.event.catalog.AvailabilityService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

/** Consumes booking outcomes published (via outbox) by booking-service. */
@Component
class BookingEventsListener {

    static final String CONFIRMED = "booking.confirmed";
    static final String CANCELLED = "booking.cancelled";

    private final ObjectMapper json;
    private final SeatFinalizer finalizer;
    private final AvailabilityService availability;

    BookingEventsListener(ObjectMapper json, SeatFinalizer finalizer, AvailabilityService availability) {
        this.json = json;
        this.finalizer = finalizer;
        this.availability = availability;
    }

    @KafkaListener(topics = {CONFIRMED, CANCELLED})
    void onMessage(ConsumerRecord<String, String> record) {
        Envelope env = parse(record.value());
        boolean applied = switch (record.topic()) {
            case CONFIRMED -> finalizer.confirm(env.messageId(), env.bookingId(), env.eventId(), env.seatCount());
            case CANCELLED -> finalizer.release(env.messageId(), env.bookingId(), env.eventId());
            default -> false;
        };
        if (applied) {
            availability.evict(env.eventId());   // after commit: display count refreshes
        }
    }

    private record Envelope(UUID messageId, UUID bookingId, long eventId, int seatCount) {}

    private Envelope parse(String value) {
        try {
            JsonNode root = json.readTree(value);
            JsonNode payload = required(root, "payload");
            int seats = payload.has("seatIds") ? payload.get("seatIds").size() : 0;
            return new Envelope(
                    UUID.fromString(required(root, "eventId").asString()),
                    UUID.fromString(required(payload, "bookingId").asString()),
                    required(payload, "eventId").asLong(),
                    seats);
        } catch (MalformedMessageException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new MalformedMessageException("Unparseable booking event", e);
        }
    }

    private static JsonNode required(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        if (v == null || v.isNull()) {
            throw new MalformedMessageException("Missing field '" + field + "'", null);
        }
        return v;
    }
}
