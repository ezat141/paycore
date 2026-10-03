package com.paycore.payment.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Publishes outbox rows to Kafka at least once (spec §5.1). Never on a request path: payments
 * keep working while Kafka is down, and the backlog drains when it returns.
 *
 * <p>Each pass claims rows in {@code seq} order, sends them all, then waits for acknowledgements
 * oldest first and marks only the rows up to the first failure. A row that was sent but not
 * acknowledged stays unpublished and is sent again: duplicates are possible by design, and the
 * consumer absorbs them.
 *
 * <p>Each row travels with its own future, so a send that fails immediately cannot shift the
 * acknowledgements onto the wrong rows. Sending stops at that row: the rows after it are not sent,
 * and they stay unpublished with it.
 *
 * <p>The pass's transaction stays open while its batch is in flight (spec §5.2). Against a broker
 * that never answers, that is at most one {@code max.block.ms} (the first send that cannot get
 * metadata fails, and sending stops) plus {@code delivery.timeout.ms} (the batch's
 * acknowledgements fail together). {@code paycore.outbox.send-timeout} covers that sum.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxStore store;
    private final EventPublisher publisher;
    private final OutboxProperties properties;
    private final TransactionTemplate transaction;

    public OutboxRelay(OutboxStore store, EventPublisher publisher, OutboxProperties properties,
                       PlatformTransactionManager transactionManager) {
        this.store = store;
        this.publisher = publisher;
        this.properties = properties;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${paycore.outbox.poll-interval-ms:500}")
    void scheduledPass() {
        if (properties.relayEnabled()) {
            relayOnce();
        }
    }

    /** One pass. Returns how many rows the broker acknowledged and were marked published. */
    public int relayOnce() {
        Integer published = transaction.execute(status -> {
            List<OutboxRow> rows = store.claimUnpublished(properties.batchSize());
            if (rows.isEmpty()) {
                return 0;
            }
            List<InFlight> sent = new ArrayList<>();
            for (OutboxRow row : rows) {
                try {
                    sent.add(new InFlight(row, publisher.publish(row)));
                } catch (RuntimeException e) {
                    log.warn("outbox send of event {} failed immediately: {}", row.id(), e.toString());
                    break;
                }
            }
            List<UUID> acknowledged = new ArrayList<>();
            for (InFlight send : sent) {
                try {
                    send.future().get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
                    acknowledged.add(send.row().id());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (ExecutionException | TimeoutException e) {
                    log.warn("outbox event {} was not acknowledged; it stays unpublished: {}",
                            send.row().id(), e.toString());
                    break;
                }
            }
            store.markPublished(acknowledged);
            return acknowledged.size();
        });
        return published == null ? 0 : published;
    }

    /** A row and the future of its own send. */
    private record InFlight(OutboxRow row, CompletableFuture<?> future) {
    }
}
