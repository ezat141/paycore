package com.paycore.history.history;

import com.paycore.history.support.HistoryServiceTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class HistoryStoreTest extends HistoryServiceTest {

    private static final Instant AT = Instant.parse("2026-10-03T10:15:30.123456Z");

    @Autowired
    HistoryStore store;

    /** I8 at the store: the same event twice is one row. */
    @Test
    void theSameEventStoredTwiceIsOneRow() {
        PaymentStatusChanged event = event(UUID.randomUUID(), UUID.randomUUID(), "acme", 0, null, "PENDING");

        assertThat(store.insertIfAbsent(event)).isTrue();
        assertThat(store.insertIfAbsent(event)).isFalse();
        assertThat(store.find("acme", event.paymentId())).hasSize(1);
    }

    /** I9 at the store: arrival order does not matter; reads come back in version order. */
    @Test
    void historyIsReadInVersionOrderWhateverTheArrivalOrder() {
        UUID payment = UUID.randomUUID();
        store.insertIfAbsent(event(UUID.randomUUID(), payment, "acme", 3, "CAPTURING", "CAPTURED"));
        store.insertIfAbsent(event(UUID.randomUUID(), payment, "acme", 0, null, "PENDING"));
        store.insertIfAbsent(event(UUID.randomUUID(), payment, "acme", 2, "AUTHORIZED", "CAPTURING"));
        store.insertIfAbsent(event(UUID.randomUUID(), payment, "acme", 1, "PENDING", "AUTHORIZED"));
        // a second payment of the same tenant must not leak into this one's history
        store.insertIfAbsent(event(UUID.randomUUID(), UUID.randomUUID(), "acme", 0, null, "PENDING"));

        assertThat(store.find("acme", payment)).extracting(HistoryEntry::version).containsExactly(0L, 1L, 2L, 3L);
    }

    /** I12 at the store: another tenant's payment has no history for you. */
    @Test
    void anotherTenantSeesNothing() {
        UUID payment = UUID.randomUUID();
        store.insertIfAbsent(event(UUID.randomUUID(), payment, "acme", 0, null, "PENDING"));

        assertThat(store.find("globex", payment)).isEmpty();
    }

    /** Every field the read API returns survives the round trip, including decline code and microseconds. */
    @Test
    void aStoredEventReadsBackWhatWasWritten() {
        UUID payment = UUID.randomUUID();
        store.insertIfAbsent(new PaymentStatusChanged(UUID.randomUUID(), payment, "acme", 1, "PENDING", "DECLINED",
                1999L, "USD", "insufficient_funds", AT));

        assertThat(store.find("acme", payment))
                .containsExactly(new HistoryEntry(1, "PENDING", "DECLINED", "insufficient_funds", AT));
    }

    private static PaymentStatusChanged event(UUID eventId, UUID paymentId, String tenant, long version,
                                              String from, String to) {
        return new PaymentStatusChanged(eventId, paymentId, tenant, version, from, to, 1999L, "USD", null, AT);
    }
}
