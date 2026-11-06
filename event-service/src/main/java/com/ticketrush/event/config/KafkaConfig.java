package com.ticketrush.event.config;

import com.ticketrush.event.messaging.MalformedMessageException;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

/**
 * Consumer failures: 3 retries (1 s, 2 s, 4 s) then <topic>.DLT (spec §11.5).
 * Malformed messages skip the retries. Reconcilers (plan 5) re-derive state, so a DLT'd message delays
 * recovery but never makes it permanent.
 */
@Configuration
public class KafkaConfig {

    /** DLTs for the topics this service consumes (the producer, booking-service, declares the topics themselves). */
    @Bean
    org.apache.kafka.clients.admin.NewTopic bookingConfirmedDlt() {
        return org.springframework.kafka.config.TopicBuilder.name("booking.confirmed.DLT").partitions(1).replicas(1).build();
    }

    @Bean
    org.apache.kafka.clients.admin.NewTopic bookingCancelledDlt() {
        return org.springframework.kafka.config.TopicBuilder.name("booking.cancelled.DLT").partitions(1).replicas(1).build();
    }

    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template) {
        var recoverer = new DeadLetterPublishingRecoverer(template,
                (record, ex) -> new TopicPartition(record.topic() + ".DLT", -1));   // -1: let Kafka pick the partition
        var backOff = new ExponentialBackOffWithMaxRetries(3);
        backOff.setInitialInterval(1_000);
        backOff.setMultiplier(2.0);
        var handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(MalformedMessageException.class);
        return handler;
    }
}
