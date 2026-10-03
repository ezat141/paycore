package com.paycore.payment.outbox;

import com.paycore.payment.payment.PaymentStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * One payment status change (spec §4.1), as other services see it. Deliberately without the
 * card token or the acquirer's authorization id. The wire shape is pinned by
 * {@code contracts/payment-status-changed.json}.
 */
public record PaymentStatusChanged(
        UUID eventId,
        UUID paymentId,
        String tenant,
        long version,
        PaymentStatus fromStatus,
        PaymentStatus toStatus,
        long amount,
        String currency,
        String declineCode,
        Instant occurredAt) {

    public static final String TYPE = "PaymentStatusChanged";
}
