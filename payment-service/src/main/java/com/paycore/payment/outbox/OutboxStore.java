package com.paycore.payment.outbox;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/** The relay's view of {@code outbox_events}. Both methods run inside the relay's transaction. */
@Repository
public class OutboxStore {

    private final JdbcClient jdbc;

    public OutboxStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Claims the oldest unpublished rows in {@code seq} order. {@code SKIP LOCKED} lets two passes,
     * or two instances, take disjoint rows instead of waiting on each other.
     */
    public List<OutboxRow> claimUnpublished(int limit) {
        return jdbc.sql("""
                        SELECT id, seq, aggregate_id, event_type, payload::text AS payload
                        FROM outbox_events
                        WHERE published_at IS NULL
                        ORDER BY seq
                        LIMIT :limit
                        FOR UPDATE SKIP LOCKED""")
                .param("limit", limit)
                .query((rs, row) -> new OutboxRow(
                        rs.getObject("id", UUID.class),
                        rs.getLong("seq"),
                        rs.getObject("aggregate_id", UUID.class),
                        rs.getString("event_type"),
                        rs.getString("payload")))
                .list();
    }

    public void markPublished(List<UUID> ids) {
        if (ids.isEmpty()) {
            return;
        }
        jdbc.sql("UPDATE outbox_events SET published_at = now() WHERE id IN (:ids)")
                .param("ids", ids)
                .update();
    }
}
