package com.ticketrush.event.messaging;

import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Spec §11.5: dead-lettered messages are made visible (ERROR log + kafka.dlt.messages{topic}); replay is manual
 * (docs/runbook.md). Reconcilers re-derive state, so a DLT'd message delays recovery but never loses it.
 */
@Component
class DeadLetterLogger {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterLogger.class);

    private final MeterRegistry meters;

    DeadLetterLogger(MeterRegistry meters) {
        this.meters = meters;
    }

    @KafkaListener(topics = {"booking.confirmed.DLT", "booking.cancelled.DLT"}, groupId = "event-service-dlt")
    void onDeadLetter(ConsumerRecord<String, String> record) {
        try {
            Header reason = record.headers().lastHeader(KafkaHeaders.DLT_EXCEPTION_MESSAGE);
            log.error("Dead-lettered message on {} (key={}, offset={}): {}", record.topic(), record.key(), record.offset(),
                    reason == null ? "no reason header" : new String(reason.value(), StandardCharsets.UTF_8));
        } finally {
            meters.counter("kafka.dlt.messages", "topic", record.topic()).increment();
        }
    }
}
