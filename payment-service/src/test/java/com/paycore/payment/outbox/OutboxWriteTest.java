package com.paycore.payment.outbox;

import com.paycore.payment.support.PaymentServiceTest;
import com.paycore.payment.support.Tokens;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.paycore.payment.support.Requests.body;
import static com.paycore.payment.support.Requests.read;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Spec §4.3: one outbox row per real status change, in the change's own transaction. */
class OutboxWriteTest extends PaymentServiceTest {

    /** I1: create then capture is exactly four status changes, each carrying its committed version. */
    @Test
    void everyStatusChangeWritesOneEventCarryingItsCommittedVersion() throws Exception {
        String id = read(create(Tokens.writer("acme"), "order-1", body(1999, "tok_approve"))
                .andExpect(status().isCreated()), "$.id");
        assertThat(lastVersion(id)).isEqualTo(storedVersion(id));

        capture(id).andExpect(status().isOk());

        List<Map<String, Object>> events = outboxEvents(id);
        assertThat(events).extracting(e -> e.get("fromStatus"))
                .containsExactly(null, "PENDING", "AUTHORIZED", "CAPTURING");
        assertThat(events).extracting(e -> e.get("toStatus"))
                .containsExactly("PENDING", "AUTHORIZED", "CAPTURING", "CAPTURED");
        List<Long> versions = events.stream().map(e -> ((Number) e.get("version")).longValue()).toList();
        assertThat(versions).isSorted().doesNotHaveDuplicates();
        assertThat(lastVersion(id)).isEqualTo(storedVersion(id));
        assertThat(events).allSatisfy(e -> {
            assertThat(e.get("paymentId")).isEqualTo(id);
            assertThat(e.get("tenant")).isEqualTo("acme");
            assertThat(((Number) e.get("amount")).longValue()).isEqualTo(1999L);
            assertThat(e.get("currency")).isEqualTo("USD");
        });
    }

    /** I2: the outcome is recorded, its event written, then the transaction fails: no event survives. */
    @Test
    void aChangeThatRollsBackLeavesNoEvent() throws Exception {
        doThrow(new DataAccessResourceFailureException("db down"))
                .when(idempotencyKeyStore).complete(anyString(), anyString(), anyInt(), anyString());

        String paymentId = read(create(Tokens.writer("acme"), "order-1", body(1999, "tok_approve"))
                .andExpect(status().isBadGateway()), "$.paymentId");

        assertThat(outboxEvents(paymentId)).extracting(e -> e.get("toStatus")).containsExactly("PENDING");
    }

    /** I3: a replay and a repeated capture change nothing, so they write nothing. */
    @Test
    void replaysAndRepeatedCapturesWriteNothing() throws Exception {
        String id = read(create(Tokens.writer("acme"), "order-1", body(1999, "tok_approve")), "$.id");
        create(Tokens.writer("acme"), "order-1", body(1999, "tok_approve")).andExpect(status().isCreated());
        capture(id).andExpect(status().isOk());
        capture(id).andExpect(status().isOk());

        assertThat(outboxEvents(id)).extracting(e -> e.get("toStatus"))
                .containsExactly("PENDING", "AUTHORIZED", "CAPTURING", "CAPTURED");
    }

    /** I3: a stale re-claim refreshes a timestamp, not the status, so CAPTURING appears once. */
    @Test
    void aStaleReClaimWritesNoSecondCapturingEvent() throws Exception {
        String id = read(create(Tokens.writer("acme"), "order-1", body(1999, "tok_approve")), "$.id");
        acquirer.failNextActions(1);
        capture(id).andExpect(status().isBadGateway());
        jdbc.sql("UPDATE payments SET transition_started_at = now() - interval '1 hour' WHERE id = :id")
                .param("id", UUID.fromString(id)).update();

        capture(id).andExpect(status().isOk());

        assertThat(outboxEvents(id)).extracting(e -> e.get("toStatus"))
                .containsExactly("PENDING", "AUTHORIZED", "CAPTURING", "CAPTURED");
    }

    /** I3: a re-drive that again gets no answer leaves the payment UNKNOWN, and writes nothing more. */
    @Test
    void anUnknownPaymentThatStaysUnknownWritesNothingMore() throws Exception {
        String paymentId = read(create(Tokens.writer("acme"), "order-1", body(1999, "tok_error"))
                .andExpect(status().isBadGateway()), "$.paymentId");
        create(Tokens.writer("acme"), "order-1", body(1999, "tok_error")).andExpect(status().isBadGateway());

        assertThat(outboxEvents(paymentId)).extracting(e -> e.get("toStatus"))
                .containsExactly("PENDING", "UNKNOWN");
    }

    /**
     * I4: twenty identical concurrent creates are still one payment, so still two events, not forty.
     * Redis is failed open so all twenty reach Postgres: with it up, its lock turns nineteen away
     * before PaymentWrites.start, and an event emitted before the key check would go unseen.
     */
    @Test
    void twentyConcurrentIdenticalCreatesWriteTwoEvents() throws Exception {
        doThrow(new RedisConnectionFailureException("redis down")).when(redis).opsForValue();
        acquirer.authorizeDelay(Duration.ofMillis(300));
        ExecutorService pool = Executors.newFixedThreadPool(20);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return create(Tokens.writer("acme"), "order-burst", body(1999, "tok_approve"))
                            .andReturn().getResponse().getStatus();
                }));
            }
            go.countDown();
            for (Future<Integer> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        verify(paymentWrites, times(20)).start(any(), any(), any(), any(), any());
        assertThat(paymentCount()).isEqualTo(1);
        assertThat(outboxCount()).isEqualTo(2);
    }

    private long lastVersion(String id) {
        List<Map<String, Object>> events = outboxEvents(id);
        return ((Number) events.get(events.size() - 1).get("version")).longValue();
    }

    private org.springframework.test.web.servlet.ResultActions capture(String id) throws Exception {
        return mockMvc.perform(post("/payments/{id}/capture", id).with(Tokens.writer("acme")));
    }
}
