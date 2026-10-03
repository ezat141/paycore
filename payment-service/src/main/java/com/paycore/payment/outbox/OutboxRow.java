package com.paycore.payment.outbox;

import java.util.UUID;

/** An unpublished outbox row, claimed by a relay pass. */
public record OutboxRow(UUID id, long seq, UUID aggregateId, String eventType, String payload) {
}
