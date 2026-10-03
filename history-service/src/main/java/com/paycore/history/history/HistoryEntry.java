package com.paycore.history.history;

import java.time.Instant;

/** One status change as the read API returns it. */
public record HistoryEntry(long version, String fromStatus, String toStatus, String declineCode, Instant occurredAt) {
}
