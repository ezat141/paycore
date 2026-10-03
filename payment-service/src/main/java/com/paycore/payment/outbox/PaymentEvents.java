package com.paycore.payment.outbox;

import com.paycore.payment.payment.Payment;
import com.paycore.payment.payment.PaymentRepository;
import com.paycore.payment.payment.PaymentStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

/**
 * Writes the outbox row for a payment status change (spec §4.3), inside the caller's
 * transaction. {@code MANDATORY}: it must never commit on its own, or an event could outlive
 * a change that rolled back.
 */
@Component
public class PaymentEvents {

    /** Its own mapper: the payload is a wire contract and must not follow Boot's global Jackson settings. */
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final PaymentRepository payments;
    private final JdbcClient jdbc;

    public PaymentEvents(PaymentRepository payments, JdbcClient jdbc) {
        this.payments = payments;
        this.jdbc = jdbc;
    }

    /** Writes an event when, and only when, the status changed from {@code from}. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void statusChanged(Payment payment, PaymentStatus from) {
        if (payment.getStatus() == from) {
            return;
        }
        // @Version is incremented at flush, so the version is read only after one (spec §4.3).
        // Flushing through the repository proxy keeps exception translation: a lost optimistic
        // lock still surfaces as Spring's OptimisticLockingFailureException, which callers handle.
        payments.flush();
        PaymentStatusChanged event = new PaymentStatusChanged(UUID.randomUUID(), payment.getId(),
                payment.getTenant(), payment.getVersion(), from, payment.getStatus(),
                payment.getAmountMinor(), payment.getCurrency(), payment.getDeclineCode(),
                payment.getUpdatedAt());
        jdbc.sql("""
                        INSERT INTO outbox_events (id, aggregate_id, event_type, payload)
                        VALUES (:id, :aggregateId, :type, CAST(:payload AS jsonb))""")
                .param("id", event.eventId())
                .param("aggregateId", event.paymentId())
                .param("type", PaymentStatusChanged.TYPE)
                .param("payload", JSON.writeValueAsString(event))
                .update();
    }
}
