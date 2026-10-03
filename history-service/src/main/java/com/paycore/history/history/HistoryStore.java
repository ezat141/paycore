package com.paycore.history.history;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Repository
public class HistoryStore {

    private final JdbcClient jdbc;

    public HistoryStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Stores the event once. Returns false when it was already stored (a redelivery). */
    public boolean insertIfAbsent(PaymentStatusChanged event) {
        return jdbc.sql("""
                        INSERT INTO payment_history (event_id, payment_id, tenant, version, from_status,
                                                     to_status, amount, currency, decline_code, occurred_at)
                        VALUES (:eventId, :paymentId, :tenant, :version, :fromStatus,
                                :toStatus, :amount, :currency, :declineCode, :occurredAt)
                        ON CONFLICT (event_id) DO NOTHING""")
                .param("eventId", event.eventId())
                .param("paymentId", event.paymentId())
                .param("tenant", event.tenant())
                .param("version", event.version())
                .param("fromStatus", event.fromStatus(), Types.VARCHAR)
                .param("toStatus", event.toStatus())
                .param("amount", event.amount())
                .param("currency", event.currency())
                .param("declineCode", event.declineCode(), Types.VARCHAR)
                .param("occurredAt", OffsetDateTime.ofInstant(event.occurredAt(), ZoneOffset.UTC))
                .update() == 1;
    }

    /** One tenant's view of one payment, in version order. Another tenant's payment is empty. */
    public List<HistoryEntry> find(String tenant, UUID paymentId) {
        return jdbc.sql("""
                        SELECT version, from_status, to_status, decline_code, occurred_at
                        FROM payment_history
                        WHERE tenant = :tenant AND payment_id = :paymentId
                        ORDER BY version""")
                .param("tenant", tenant)
                .param("paymentId", paymentId)
                .query((rs, row) -> new HistoryEntry(
                        rs.getLong("version"),
                        rs.getString("from_status"),
                        rs.getString("to_status"),
                        rs.getString("decline_code"),
                        rs.getObject("occurred_at", OffsetDateTime.class).toInstant()))
                .list();
    }
}
