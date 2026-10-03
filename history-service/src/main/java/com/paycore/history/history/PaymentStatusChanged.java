package com.paycore.history.history;

import java.time.Instant;
import java.util.UUID;

/**
 * history-service's own copy of the event (spec §4.1). Statuses are strings here: this service
 * does not own the payment state machine, it records what payment-service reported.
 */
public record PaymentStatusChanged(
        UUID eventId,
        UUID paymentId,
        String tenant,
        long version,
        String fromStatus,
        String toStatus,
        long amount,
        String currency,
        String declineCode,
        Instant occurredAt) {
}
