package com.paycore.payment.outbox;

import com.paycore.payment.support.PaymentServiceTest;
import com.paycore.payment.support.Tokens;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.KafkaException;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static com.paycore.payment.support.Requests.body;
import static com.paycore.payment.support.Requests.read;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spec §5: the relay publishes every committed event at least once, in seq order.
 *
 * <p>At least once means a record can reach Kafka more than once: a broker that comes back
 * delivers what it already had, and the relay sends it again. Every assertion on what Kafka holds
 * therefore keeps the first copy of each {@code eventId} and checks the order of those.
 */
class OutboxRelayTest extends PaymentServiceTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    OutboxRelay relay;

    @Autowired
    OutboxStore store;

    @Autowired
    OutboxProperties properties;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    KafkaContainer kafka;

    /** I7: three payments, each created then captured; every payment's events reach Kafka in commit order. */
    @Test
    void eachPaymentsEventsReachKafkaInCommitOrder() throws Exception {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String id = read(create(Tokens.writer("acme"), "order-" + i, body(1999, "tok_approve")), "$.id");
            mockMvc.perform(post("/payments/{id}/capture", id).with(Tokens.writer("acme"))).andExpect(status().isOk());
            ids.add(id);
        }

        assertThat(relay.relayOnce()).isEqualTo(12);

        for (String id : ids) {
            List<ConsumerRecord<String, String>> records = recordsFor(id, 4);
            assertThat(firstOfEachEvent(records)).extracting(r -> toStatus(r.value()))
                    .containsExactly("PENDING", "AUTHORIZED", "CAPTURING", "CAPTURED");
            assertThat(records).allSatisfy(r -> {
                assertThat(header(r, "eventId")).isEqualTo(eventId(r.value()));
                assertThat(header(r, "eventType")).isEqualTo(PaymentStatusChanged.TYPE);
            });
        }
        assertThat(unpublishedCount()).isZero();
    }

    /** I6: a send the broker never acknowledged is not marked, so the next pass sends it again. */
    @Test
    void anUnacknowledgedSendIsSentAgainOnTheNextPass() throws Exception {
        String id = read(create(Tokens.writer("acme"), "order-1", body(1999, "tok_approve")), "$.id");
        doCallRealMethod()
                .doReturn(CompletableFuture.failedFuture(new IllegalStateException("broker did not acknowledge")))
                .when(eventPublisher).publish(any(OutboxRow.class));

        assertThat(relay.relayOnce()).isEqualTo(1);
        assertThat(unpublishedStatuses(id)).containsExactly("AUTHORIZED");

        Mockito.reset(eventPublisher);
        assertThat(relay.relayOnce()).isEqualTo(1);
        assertThat(unpublishedCount()).isZero();
        assertThat(firstOfEachEvent(recordsFor(id, 2))).extracting(r -> toStatus(r.value()))
                .containsExactly("PENDING", "AUTHORIZED");
    }

    /**
     * A send that throws instead of returning a future ends the pass there. The rows before it are
     * marked once acknowledged; the failed row and every row after it stay unpublished and are not
     * sent at all, so no later row can be taken for an earlier one.
     */
    @Test
    void aSendThatFailsImmediatelyStopsThePassAndMarksOnlyWhatWasAcknowledged() throws Exception {
        create(Tokens.writer("acme"), "order-1", body(1999, "tok_approve")).andExpect(status().isCreated());
        create(Tokens.writer("acme"), "order-2", body(1999, "tok_approve")).andExpect(status().isCreated());
        List<UUID> rows = eventIdsInSeqOrder();
        assertThat(rows).hasSize(4);
        doCallRealMethod()
                .doCallRealMethod()
                .doThrow(new KafkaException("Send failed"))
                .doCallRealMethod()
                .when(eventPublisher).publish(any(OutboxRow.class));

        assertThat(relay.relayOnce()).isEqualTo(2);

        assertThat(publishedEventIds()).containsExactlyElementsOf(rows.subList(0, 2));
        verify(eventPublisher, times(3)).publish(any(OutboxRow.class));
    }

    /**
     * A pass claims at most {@code batch-size} rows, the oldest first. The relay is built here, with
     * a batch size of 2, from the context's own beans: setting the property instead would fork the
     * shared Spring context and start a second set of containers.
     */
    @Test
    void aPassPublishesAtMostTheBatchSize() throws Exception {
        create(Tokens.writer("acme"), "order-1", body(1999, "tok_approve")).andExpect(status().isCreated());
        create(Tokens.writer("acme"), "order-2", body(1999, "tok_approve")).andExpect(status().isCreated());
        List<UUID> rows = eventIdsInSeqOrder();
        assertThat(rows).hasSize(4);
        OutboxProperties batchOfTwo = new OutboxProperties(false, properties.pollIntervalMs(), 2,
                properties.sendTimeout(), properties.topicPartitions(), properties.topicReplicas());
        OutboxRelay smallBatches = new OutboxRelay(store, eventPublisher, batchOfTwo, transactionManager);

        assertThat(smallBatches.relayOnce()).isEqualTo(2);
        assertThat(publishedEventIds()).containsExactlyElementsOf(rows.subList(0, 2));

        assertThat(smallBatches.relayOnce()).isEqualTo(2);
        assertThat(publishedEventIds()).containsExactlyElementsOf(rows);
    }

    /**
     * I5: payments are created while Kafka is down; the backlog drains, in order, once it returns.
     * The producer is warmed first (metadata fetched, connection open), so this is Kafka dying
     * mid-operation, whichever test ran before it.
     */
    @Test
    void paymentsKeepWorkingWhileKafkaIsDownAndTheBacklogDrainsAfter() throws Exception {
        create(Tokens.writer("acme"), "order-0", body(1999, "tok_approve")).andExpect(status().isCreated());
        assertThat(relay.relayOnce()).isEqualTo(2);
        Mockito.clearInvocations(eventPublisher);

        var docker = DockerClientFactory.instance().client();
        docker.pauseContainerCmd(kafka.getContainerId()).exec();
        String id;
        try {
            long started = System.nanoTime();
            id = read(create(Tokens.writer("acme"), "order-1", body(1999, "tok_approve"))
                    .andExpect(status().isCreated()), "$.id");
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
            // The timing bound alone depends on the producer's state and its timeouts.
            // Nothing on the request path may reach the publisher at all, whatever it costs.
            verify(eventPublisher, never()).publish(any(OutboxRow.class));

            assertThat(relay.relayOnce()).isZero();
            assertThat(unpublishedStatuses(id)).containsExactly("PENDING", "AUTHORIZED");
        } finally {
            docker.unpauseContainerCmd(kafka.getContainerId()).exec();
        }

        int[] published = {0};
        await().atMost(Duration.ofSeconds(30)).until(() -> (published[0] += relay.relayOnce()) >= 2);
        // The batch already written to the paused broker can land when it resumes, besides the
        // relay's second send of the same rows: two copies of each event, which is at least once.
        assertThat(firstOfEachEvent(recordsFor(id, 2))).extracting(r -> toStatus(r.value()))
                .containsExactly("PENDING", "AUTHORIZED");
    }

    @Test
    void aPassWithNothingToPublishPublishesNothing() {
        assertThat(relay.relayOnce()).isZero();
    }

    /**
     * Every record for one payment, duplicates included. Reads until at least {@code expected} have
     * arrived and two polls in a row return nothing, so copies arriving after the expected count
     * are seen too. Gives up after 20 seconds.
     */
    private List<ConsumerRecord<String, String>> recordsFor(String paymentId, int expected) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "relay-test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
            consumer.subscribe(List.of(KafkaEventPublisher.TOPIC));
            long deadline = System.currentTimeMillis() + 20_000;
            int emptyPolls = 0;
            while (System.currentTimeMillis() < deadline && !(found.size() >= expected && emptyPolls >= 2)) {
                ConsumerRecords<String, String> polled = consumer.poll(Duration.ofMillis(500));
                emptyPolls = polled.isEmpty() ? emptyPolls + 1 : 0;
                for (ConsumerRecord<String, String> record : polled) {
                    if (paymentId.equals(record.key())) {
                        found.add(record);
                    }
                }
            }
        }
        return found;
    }

    /** The first copy of each event, in the order Kafka holds them. */
    private static List<ConsumerRecord<String, String>> firstOfEachEvent(List<ConsumerRecord<String, String>> records) {
        Set<String> seen = new HashSet<>();
        return records.stream().filter(r -> seen.add(header(r, "eventId"))).toList();
    }

    private List<UUID> eventIdsInSeqOrder() {
        return jdbc.sql("SELECT id FROM outbox_events ORDER BY seq").query(UUID.class).list();
    }

    private List<UUID> publishedEventIds() {
        return jdbc.sql("SELECT id FROM outbox_events WHERE published_at IS NOT NULL ORDER BY seq")
                .query(UUID.class).list();
    }

    private long unpublishedCount() {
        return jdbc.sql("SELECT count(*) FROM outbox_events WHERE published_at IS NULL").query(Long.class).single();
    }

    private List<String> unpublishedStatuses(String paymentId) {
        return jdbc.sql("""
                        SELECT payload->>'toStatus' FROM outbox_events
                        WHERE aggregate_id = :id AND published_at IS NULL ORDER BY seq""")
                .param("id", UUID.fromString(paymentId)).query(String.class).list();
    }

    @SuppressWarnings("unchecked")
    private static String toStatus(String payload) {
        return (String) JSON.readValue(payload, Map.class).get("toStatus");
    }

    @SuppressWarnings("unchecked")
    private static String eventId(String payload) {
        return (String) JSON.readValue(payload, Map.class).get("eventId");
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }
}
