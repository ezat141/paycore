package com.paycore.payment.outbox;

import java.util.concurrent.CompletableFuture;

/** Publishes one outbox row. The future completes when the broker acknowledges it. */
public interface EventPublisher {

    CompletableFuture<?> publish(OutboxRow row);
}
