package com.paycore.history.history;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Stores each event once. The offset is committed after this method returns (ack-mode record),
 * so a crash before the insert commits means a redelivery, which the event-id key absorbs.
 */
@Component
public class PaymentHistoryListener {

    public static final String ID = "payment-history";

    private final EventParser parser;
    private final HistoryStore store;

    public PaymentHistoryListener(EventParser parser, HistoryStore store) {
        this.parser = parser;
        this.store = store;
    }

    // idIsGroup = false: otherwise the id becomes the group id and spring.kafka.consumer.group-id is ignored
    @KafkaListener(id = ID, idIsGroup = false, topics = KafkaConfig.TOPIC)
    public void onEvent(String payload) {
        store.insertIfAbsent(parser.parse(payload));
    }
}
