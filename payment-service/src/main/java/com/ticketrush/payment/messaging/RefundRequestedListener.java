package com.ticketrush.payment.messaging;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

@Component
class RefundRequestedListener {

    private final ObjectMapper json;
    private final RefundHandler handler;

    RefundRequestedListener(ObjectMapper json, RefundHandler handler) {
        this.json = json;
        this.handler = handler;
    }

    @KafkaListener(topics = RefundHandler.TOPIC)
    void onMessage(ConsumerRecord<String, String> record) {
        UUID messageId, paymentId;
        try {
            JsonNode root = json.readTree(record.value());
            messageId = UUID.fromString(root.get("eventId").asString());
            paymentId = UUID.fromString(root.get("payload").get("paymentId").asString());
        } catch (RuntimeException e) {
            // never processable: IllegalArgumentException is not retried and goes straight to the DLT
            throw new IllegalArgumentException("Malformed payment.refund.requested message", e);
        }
        handler.handle(messageId, paymentId);
    }
}
