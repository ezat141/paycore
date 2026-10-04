package com.paycore.history.history;

import com.paycore.history.support.Events;
import com.paycore.history.support.HistoryServiceTest;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Spec §6.2–6.4 against a real broker. */
class HistoryConsumerTest extends HistoryServiceTest {

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    KafkaContainer kafkaContainer;

    @Autowired
    PostgreSQLContainer postgres;

    @Autowired
    KafkaListenerEndpointRegistry registry;

    @Autowired
    HistoryStore store;

    /** Spec §6.2: the consumer group is history-service, not the listener id. */
    @Test
    void theConsumerGroupIsHistoryService() {
        assertThat(registry.getListenerContainer(PaymentHistoryListener.ID).getGroupId())
                .isEqualTo("history-service");
    }

    @Test
    void aPublishedEventIsStored() {
        UUID payment = UUID.randomUUID();
        send(payment, Events.json(UUID.randomUUID(), payment, "acme", 0, null, "PENDING"));

        awaitHistory(payment, 1);
    }

    /**
     * I8: a duplicate delivery is one row, and is not dead-lettered: redelivery is normal under
     * at-least-once. The marker on the same key proves both copies were processed.
     */
    @Test
    void aDuplicateDeliveryIsStoredOnce() {
        UUID payment = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        String event = Events.json(eventId, payment, "acme", 0, null, "PENDING");
        send(payment, event);
        send(payment, event);
        send(payment, Events.json(UUID.randomUUID(), payment, "acme", 1, "PENDING", "AUTHORIZED"));

        awaitHistory(payment, 2);
        assertThat(store.find("acme", payment)).extracting(HistoryEntry::version).containsExactly(0L, 1L);
        assertThat(dltValues(Duration.ofSeconds(4), eventId.toString())).isEmpty();
    }

    /**
     * A different event for a version already stored can never be stored: it goes to the DLT, and
     * its partition keeps moving (spec §6.4). The history keeps the first event for that version.
     */
    @Test
    void aConflictingVersionGoesToTheDltAndDoesNotBlockItsPartition() {
        UUID payment = UUID.randomUUID();
        UUID conflicting = UUID.randomUUID();
        send(payment, Events.json(UUID.randomUUID(), payment, "acme", 0, null, "PENDING"));
        send(payment, Events.json(conflicting, payment, "acme", 0, null, "PENDING"));
        send(payment, Events.json(UUID.randomUUID(), payment, "acme", 1, "PENDING", "AUTHORIZED"));

        awaitHistory(payment, 2);
        assertThat(store.find("acme", payment)).extracting(HistoryEntry::version).containsExactly(0L, 1L);
        assertThat(dltValues(Duration.ofSeconds(15), conflicting.toString())).isNotEmpty();
    }

    /** I8: events published while the consumer is stopped are stored once it starts again. */
    @Test
    void eventsPublishedWhileTheConsumerIsStoppedAreStoredWhenItRestarts() {
        UUID payment = UUID.randomUUID();
        registry.getListenerContainer(PaymentHistoryListener.ID).stop();
        try {
            send(payment, Events.json(UUID.randomUUID(), payment, "acme", 0, null, "PENDING"));
            send(payment, Events.json(UUID.randomUUID(), payment, "acme", 1, "PENDING", "AUTHORIZED"));
        } finally {
            registry.getListenerContainer(PaymentHistoryListener.ID).start();
        }

        awaitHistory(payment, 2);
    }

    /** I9: v2 delivered before v1 still reads back as v1, v2. */
    @Test
    void outOfOrderDeliveryReadsBackInVersionOrder() {
        UUID payment = UUID.randomUUID();
        send(payment, Events.json(UUID.randomUUID(), payment, "acme", 2, "AUTHORIZED", "CAPTURING"));
        send(payment, Events.json(UUID.randomUUID(), payment, "acme", 1, "PENDING", "AUTHORIZED"));

        awaitHistory(payment, 2);
        assertThat(store.find("acme", payment)).extracting(HistoryEntry::version).containsExactly(1L, 2L);
    }

    /** I10: a poison record goes to the DLT and the next record on its partition is still stored. */
    @Test
    void aPoisonRecordGoesToTheDltAndDoesNotBlockItsPartition() {
        UUID payment = UUID.randomUUID();
        String poison = "{ not json " + UUID.randomUUID();
        send(payment, poison);
        send(payment, Events.json(UUID.randomUUID(), payment, "acme", 0, null, "PENDING"));

        awaitHistory(payment, 1);
        assertThat(dltValues(Duration.ofSeconds(15), poison)).contains(poison);
    }

    /**
     * I11: while the history database is down, a valid event is retried, never sent to the DLT.
     * The outage lasts 15s, which outlasts a handler with a bounded retry count.
     */
    @Test
    void aDatabaseOutageDelaysAnEventWithoutLosingIt() {
        UUID payment = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        var docker = DockerClientFactory.instance().client();
        docker.pauseContainerCmd(postgres.getContainerId()).exec();
        try {
            send(payment, Events.json(eventId, payment, "acme", 0, null, "PENDING"));
            // Long enough to outlast a small finite retry count. A failed attempt takes up to about
            // 3s: validating a borrowed connection (validation-timeout 0.5s), or a query that hits
            // the 2s socket timeout, then the 1s back-off. A handler giving up after a few attempts
            // would have dead-lettered the event well within this window.
            assertThat(dltValues(Duration.ofSeconds(15), eventId.toString())).isEmpty();
        } finally {
            docker.unpauseContainerCmd(postgres.getContainerId()).exec();
        }

        awaitHistory(payment, 1);
    }

    /** I13: the contract's own example is consumed and stored with every field intact. */
    @Test
    @SuppressWarnings("unchecked")
    void theContractExampleIsStored() throws Exception {
        JsonMapper json = JsonMapper.builder().build();
        Map<String, Object> example = new LinkedHashMap<>(json.readValue(
                Files.readString(Path.of("../contracts/payment-status-changed.json")), Map.class));
        // put() would add these keys even if the contract renamed them, hiding the drift
        assertThat(example).containsKeys("eventId", "paymentId");
        UUID payment = UUID.randomUUID();
        example.put("eventId", UUID.randomUUID().toString());
        example.put("paymentId", payment.toString());

        send(payment, json.writeValueAsString(example));

        awaitHistory(payment, 1);
        HistoryEntry stored = store.find("acme", payment).getFirst();
        assertThat(stored.version()).isEqualTo(3L);
        assertThat(stored.fromStatus()).isEqualTo("AUTHORIZED");
        assertThat(stored.toStatus()).isEqualTo("CAPTURING");
        assertThat(stored.occurredAt()).hasToString("2026-10-03T10:15:30.123456Z");
    }

    private void send(UUID payment, String value) {
        kafka.send(KafkaConfig.TOPIC, payment.toString(), value).join();
    }

    private void awaitHistory(UUID payment, int rows) {
        await().atMost(Duration.ofSeconds(30)).until(() -> store.find("acme", payment).size() == rows);
    }

    /** Values on the DLT containing {@code marker}, read for at most {@code within}. */
    private List<String> dltValues(Duration within, String marker) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "dlt-test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<String> found = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
            consumer.subscribe(List.of(KafkaConfig.DLT));
            long deadline = System.currentTimeMillis() + within.toMillis();
            while (found.isEmpty() && System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (record.value() != null && record.value().contains(marker)) {
                        found.add(record.value());
                    }
                }
            }
        }
        return found;
    }
}
