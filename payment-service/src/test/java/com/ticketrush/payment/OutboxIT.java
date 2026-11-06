package com.ticketrush.payment;

import com.ticketrush.payment.outbox.OutboxRelay;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxIT extends IntegrationTestBase {

    @Autowired
    OutboxRelay relay;

    static KafkaConsumer<String, String> consumer(String topic) {
        var c = new KafkaConsumer<String, String>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"), new StringDeserializer(), new StringDeserializer());
        c.subscribe(List.of(topic));
        return c;
    }

    /** Review Focus 5: crash after the Kafka send, before published_at is committed -> sent again, same eventId. */
    @Test
    void relayCrashBeforeMarkPublishesTwiceWithOneEventId() throws Exception {
        long u = uniqueUser();
        UUID b = pendingBooking(u);
        pay(u, "k-kill", b);
        String outboxId = jdbc.queryForObject("SELECT BIN_TO_UUID(id) FROM outbox_events WHERE aggregate_id=UUID_TO_BIN(?)",
                String.class, b.toString());

        relay.publishBatch();
        jdbc.update("UPDATE outbox_events SET published_at = NULL WHERE id = UUID_TO_BIN(?)", outboxId);   // the mark never committed
        relay.publishBatch();

        try (var c = consumer("payment.succeeded")) {
            List<String> eventIds = new ArrayList<>();
            for (int i = 0; i < 40 && eventIds.size() < 2; i++) {
                c.poll(Duration.ofMillis(500)).forEach(r -> {
                    if (r.key().equals(b.toString())) eventIds.add(json.readTree(r.value()).get("eventId").asString());
                });
            }
            assertThat(eventIds).containsExactly(outboxId, outboxId);   // at-least-once; consumers dedupe by eventId
        }
    }

    @Test
    void relayPublishesPaymentSucceededKeyedByBookingWithOutboxIdAsEventId() throws Exception {
        long u = uniqueUser();
        UUID b = pendingBooking(u);
        String paymentId = bodyOf(pay(u, "k1", b)).get("id").asString();
        String outboxId = jdbc.queryForObject("SELECT BIN_TO_UUID(id) FROM outbox_events WHERE aggregate_id=UUID_TO_BIN(?)",
                String.class, b.toString());

        relay.publishBatch();

        assertThat(jdbc.queryForObject("SELECT published_at IS NOT NULL FROM outbox_events WHERE id=UUID_TO_BIN(?)",
                Boolean.class, outboxId)).isTrue();
        try (var c = consumer("payment.succeeded")) {
            List<ConsumerRecord<String, String>> found = new ArrayList<>();
            for (int i = 0; i < 40 && found.isEmpty(); i++) {
                c.poll(Duration.ofMillis(500)).forEach(r -> { if (r.key().equals(b.toString())) found.add(r); });
            }
            assertThat(found).hasSize(1);
            var envelope = json.readTree(found.getFirst().value());
            assertThat(envelope.get("eventId").asString()).isEqualTo(outboxId);
            assertThat(envelope.get("eventType").asString()).isEqualTo("payment.succeeded");
            assertThat(envelope.get("payload").get("paymentId").asString()).isEqualTo(paymentId);
        }
    }
}
