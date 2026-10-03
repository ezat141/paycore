package com.paycore.history.support;

import java.util.UUID;

/** Event payloads in the contract's shape (contracts/payment-status-changed.json). */
public final class Events {

    private Events() {
    }

    public static String json(UUID eventId, UUID paymentId, String tenant, long version,
                              String fromStatus, String toStatus) {
        return """
                {"eventId":"%s","paymentId":"%s","tenant":"%s","version":%d,"fromStatus":%s,
                 "toStatus":"%s","amount":1999,"currency":"USD","declineCode":null,
                 "occurredAt":"2026-10-03T10:15:30.123456Z"}"""
                .formatted(eventId, paymentId, tenant, version,
                        fromStatus == null ? "null" : "\"" + fromStatus + "\"", toStatus);
    }
}
