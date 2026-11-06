package com.ticketrush.booking.messaging;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

@Component
class PaymentSucceededListener {

    private final ObjectMapper json;
    private final PaymentSucceededHandler handler;

    PaymentSucceededListener(ObjectMapper json, PaymentSucceededHandler handler) {
        this.json = json;
        this.handler = handler;
    }

    @KafkaListener(topics = PaymentSucceededHandler.TOPIC)
    void onMessage(ConsumerRecord<String, String> record) {
        UUID messageId, paymentId, bookingId;
        try {
            JsonNode root = json.readTree(record.value());
            JsonNode payload = root.get("payload");
            messageId = UUID.fromString(root.get("eventId").asString());
            paymentId = UUID.fromString(payload.get("paymentId").asString());
            bookingId = UUID.fromString(payload.get("bookingId").asString());
        } catch (RuntimeException e) {
            // never processable: IllegalArgumentException is not retried and goes straight to the DLT
            throw new IllegalArgumentException("Malformed payment.succeeded message", e);
        }
        handler.handle(messageId, paymentId, bookingId);
    }
}
