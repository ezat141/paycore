package com.paycore.payment.outbox;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class OutboxConfig {

    /** Declared here because the broker has auto-creation off; three partitions, keyed by payment. */
    @Bean
    NewTopic paymentEventsTopic(OutboxProperties properties) {
        return TopicBuilder.name(KafkaEventPublisher.TOPIC)
                .partitions(properties.topicPartitions())
                .replicas(properties.topicReplicas())
                .build();
    }
}
