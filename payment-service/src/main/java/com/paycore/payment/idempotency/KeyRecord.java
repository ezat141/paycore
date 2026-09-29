package com.paycore.payment.idempotency;

import java.util.UUID;

/** A row of {@code idempotency_keys}. {@code leaseLive} is computed by Postgres, on its clock. */
public record KeyRecord(
        String fingerprint,
        UUID paymentId,
        String state,
        boolean leaseLive,
        Integer responseStatus,
        String responseBody) {

    public boolean completed() {
        return "COMPLETED".equals(state);
    }
}
