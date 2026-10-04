package com.paycore.history.history;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Topics and failure handling (spec §6.4). A record that can never be stored (invalid, or in
 * conflict with stored history) goes straight to the DLT, so it cannot stall its partition;
 * anything else (a database outage) is retried without limit, because the event is valid and
 * sending it to the DLT would lose history.
 */
@Configuration
public class KafkaConfig {

    public static final String TOPIC = "paycore.payment-events";
    public static final String DLT = "paycore.payment-events.DLT";

    /** Declared here too: whichever service starts first creates it, with the same shape. */
    @Bean
    NewTopic paymentEventsTopic(@Value("${paycore.history.topic-partitions:3}") int partitions,
                                @Value("${paycore.history.topic-replicas:1}") int replicas) {
        return TopicBuilder.name(TOPIC).partitions(partitions).replicas(replicas).build();
    }

    /** Same partition count as the topic: a dead record keeps its partition number. */
    @Bean
    NewTopic paymentEventsDlt(@Value("${paycore.history.topic-partitions:3}") int partitions,
                              @Value("${paycore.history.topic-replicas:1}") int replicas) {
        return TopicBuilder.name(DLT).partitions(partitions).replicas(replicas).build();
    }

    @Bean
    DefaultErrorHandler errorHandler(KafkaTemplate<String, String> kafka) {
        DeadLetterPublishingRecoverer toDlt = new DeadLetterPublishingRecoverer(kafka,
                (record, exception) -> new TopicPartition(DLT, record.partition()));
        DefaultErrorHandler handler = new DefaultErrorHandler(toDlt,
                new FixedBackOff(1_000L, FixedBackOff.UNLIMITED_ATTEMPTS));
        // A constraint violation is permanent: a different event for a stored version, or a value
        // too long for its column. An outage is a connection failure, never one of these.
        handler.addNotRetryableExceptions(InvalidEventException.class, DataIntegrityViolationException.class);
        return handler;
    }
}
