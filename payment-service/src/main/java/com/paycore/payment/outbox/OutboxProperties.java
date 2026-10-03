package com.paycore.payment.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("paycore.outbox")
public record OutboxProperties(
        @DefaultValue("true") boolean relayEnabled,
        @DefaultValue("500") long pollIntervalMs,
        @DefaultValue("100") int batchSize,
        @DefaultValue("15s") Duration sendTimeout,
        @DefaultValue("3") int topicPartitions,
        @DefaultValue("1") int topicReplicas) {
}
