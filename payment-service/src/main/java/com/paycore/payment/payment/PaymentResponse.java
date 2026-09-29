package com.paycore.payment.payment;

import java.time.Instant;
import java.util.UUID;

/** The wire representation. The card token, tenant and acquirer id are deliberately absent. */
public record PaymentResponse(
        UUID id,
        PaymentStatus status,
        long amount,
        String currency,
        String description,
        String declineCode,
        Instant createdAt,
        Instant updatedAt) {

    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(payment.getId(), payment.getStatus(), payment.getAmountMinor(),
                payment.getCurrency(), payment.getDescription(), payment.getDeclineCode(),
                payment.getCreatedAt(), payment.getUpdatedAt());
    }
}
