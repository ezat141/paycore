package com.paycore.payment.idempotency;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code idempotency_keys}, in SQL. Leases are compared against Postgres's {@code now()}, never a
 * JVM clock, so instances with skewed clocks still agree on who holds a key.
 */
@Repository
public class IdempotencyKeyStore {

    private final JdbcClient jdbc;

    public IdempotencyKeyStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * False when the key already exists. If another transaction has inserted it but not yet
     * committed, Postgres makes this wait for that transaction to finish, then answers correctly.
     */
    public boolean insertIfAbsent(String tenant, String key, String fingerprint, UUID paymentId, Duration lease) {
        return jdbc.sql("""
                        INSERT INTO idempotency_keys (tenant, idempotency_key, fingerprint, payment_id,
                                                      state, locked_until)
                        VALUES (:tenant, :key, :fingerprint, :paymentId, 'IN_PROGRESS',
                                now() + make_interval(secs => :leaseSeconds))
                        ON CONFLICT (tenant, idempotency_key) DO NOTHING""")
                .param("tenant", tenant)
                .param("key", key)
                .param("fingerprint", fingerprint)
                .param("paymentId", paymentId)
                .param("leaseSeconds", seconds(lease))
                .update() == 1;
    }

    public Optional<KeyRecord> find(String tenant, String key) {
        return jdbc.sql("""
                        SELECT fingerprint, payment_id, state, locked_until > now() AS lease_live,
                               response_status, response_body::text AS response_body
                        FROM idempotency_keys
                        WHERE tenant = :tenant AND idempotency_key = :key""")
                .param("tenant", tenant)
                .param("key", key)
                .query((rs, row) -> new KeyRecord(
                        rs.getString("fingerprint"),
                        rs.getObject("payment_id", UUID.class),
                        rs.getString("state"),
                        rs.getBoolean("lease_live"),
                        rs.getObject("response_status", Integer.class),
                        rs.getString("response_body")))
                .optional();
    }

    /**
     * Takes over a key whose lease has lapsed. Concurrent callers serialise on the row lock and
     * the loser re-evaluates the WHERE against the winner's new lease — so exactly one gets true.
     */
    public boolean claimExpired(String tenant, String key, Duration lease) {
        return jdbc.sql("""
                        UPDATE idempotency_keys
                        SET locked_until = now() + make_interval(secs => :leaseSeconds)
                        WHERE tenant = :tenant AND idempotency_key = :key
                          AND state = 'IN_PROGRESS' AND locked_until <= now()""")
                .param("tenant", tenant)
                .param("key", key)
                .param("leaseSeconds", seconds(lease))
                .update() == 1;
    }

    public void complete(String tenant, String key, int responseStatus, String responseBody) {
        jdbc.sql("""
                        UPDATE idempotency_keys
                        SET state = 'COMPLETED', response_status = :status, response_body = CAST(:body AS jsonb)
                        WHERE tenant = :tenant AND idempotency_key = :key""")
                .param("tenant", tenant)
                .param("key", key)
                .param("status", responseStatus)
                .param("body", responseBody)
                .update();
    }

    /** Ends the lease now, so the next retry may re-drive. The row stays IN_PROGRESS. */
    public void release(String tenant, String key) {
        jdbc.sql("""
                        UPDATE idempotency_keys SET locked_until = now()
                        WHERE tenant = :tenant AND idempotency_key = :key""")
                .param("tenant", tenant)
                .param("key", key)
                .update();
    }

    private static double seconds(Duration duration) {
        return duration.toMillis() / 1000.0;
    }
}
