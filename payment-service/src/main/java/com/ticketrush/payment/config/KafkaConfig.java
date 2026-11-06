package com.ticketrush.payment.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

/** payment-service owns (produces) payment.succeeded, so it declares it (spec §11.1). */
@Configuration
class KafkaConfig {

    @Bean
    NewTopic paymentSucceeded() {
        return TopicBuilder.name("payment.succeeded").partitions(3).replicas(1).build();
    }

    /** DLT for the topic this service consumes. */
    @Bean
    NewTopic refundRequestedDlt() {
        return TopicBuilder.name("payment.refund.requested.DLT").partitions(1).replicas(1).build();
    }

    /** 3 retries (1 s, 2 s, 4 s) then <topic>.DLT; malformed messages skip the retries. */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template) {
        var recoverer = new DeadLetterPublishingRecoverer(template, (record, ex) -> new TopicPartition(record.topic() + ".DLT", -1));
        var backOff = new ExponentialBackOffWithMaxRetries(3);
        backOff.setInitialInterval(1_000);
        backOff.setMultiplier(2.0);
        var handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(IllegalArgumentException.class);
        return handler;
    }
}
