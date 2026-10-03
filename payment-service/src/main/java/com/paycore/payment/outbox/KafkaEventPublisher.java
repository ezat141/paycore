package com.paycore.payment.outbox;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

/**
 * Sends a row to {@value #TOPIC}, keyed by payment id: one payment's events share a partition,
 * so Kafka keeps them in the order they were sent (spec §5).
 */
@Component
public class KafkaEventPublisher implements EventPublisher {

    public static final String TOPIC = "paycore.payment-events";

    private final KafkaTemplate<String, String> kafka;

    public KafkaEventPublisher(KafkaTemplate<String, String> kafka) {
        this.kafka = kafka;
    }

    @Override
    public CompletableFuture<?> publish(OutboxRow row) {
        ProducerRecord<String, String> record =
                new ProducerRecord<>(TOPIC, row.aggregateId().toString(), row.payload());
        record.headers().add("eventId", row.id().toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventType", row.eventType().getBytes(StandardCharsets.UTF_8));
        return kafka.send(record);
    }
}
