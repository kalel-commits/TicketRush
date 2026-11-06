package com.ticketrush.booking;

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

    double dlt() {
        return meters.counter("kafka.dlt.messages", "topic", "payment.succeeded.DLT").count();
    }

    @Test
    void malformedMessageIsCountedOnItsDeadLetterTopic() throws Exception {
        double before = dlt();
        kafka.send("payment.succeeded", "bad-" + UUID.randomUUID(), "{not json").get();
        await().atMost(Duration.ofSeconds(20)).until(() -> dlt() >= before + 1);
    }
}
