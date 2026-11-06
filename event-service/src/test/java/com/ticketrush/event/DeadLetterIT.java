package com.ticketrush.event;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.util.UUID;

import static org.awaitility.Awaitility.await;

/** Spec §11.5: a dead-lettered message is visible (ERROR log + kafka.dlt.messages{topic}). */
class DeadLetterIT extends IntegrationTestBase {

    @Autowired
    KafkaTemplate<String, String> kafka;
    @Autowired
    MeterRegistry meters;

    double dlt(String topic) {
        return meters.counter("kafka.dlt.messages", "topic", topic).count();
    }

    @Test
    void malformedMessagesAreCountedOnTheirDeadLetterTopics() throws Exception {
        double confirmed = dlt("booking.confirmed.DLT"), cancelled = dlt("booking.cancelled.DLT");
        kafka.send("booking.confirmed", "bad-" + UUID.randomUUID(), "{not json").get();
        kafka.send("booking.cancelled", "bad-" + UUID.randomUUID(), "{not json").get();
        await().atMost(Duration.ofSeconds(20)).until(() -> dlt("booking.confirmed.DLT") >= confirmed + 1
                && dlt("booking.cancelled.DLT") >= cancelled + 1);
    }
}
