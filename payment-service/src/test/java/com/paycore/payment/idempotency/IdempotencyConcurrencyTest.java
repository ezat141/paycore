package com.paycore.payment.idempotency;

import com.paycore.payment.support.PaymentServiceTest;
import com.paycore.payment.support.Tokens;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.paycore.payment.support.Requests.body;
import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyConcurrencyTest extends PaymentServiceTest {

    /**
     * Twenty copies of the same request released at once, with the acquirer slow enough that
     * they overlap. Exactly one charge; everyone else is told to wait or handed the replay.
     */
    @Test
    void twentyConcurrentRetriesProduceOnePaymentAndOneCharge() throws Exception {
        acquirer.authorizeDelay(Duration.ofMillis(300));
        List<Integer> statuses = fire(20, "order-burst");

        assertThat(statuses).allMatch(status -> status == 201 || status == 409).contains(201);
        assertThat(paymentCount()).isEqualTo(1);
        assertThat(acquirer.totalAuthorizeCalls()).isEqualTo(1);
    }

    private List<Integer> fire(int copies, String idempotencyKey) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(copies);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < copies; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return create(Tokens.writer("acme"), idempotencyKey, body(1999, "tok_approve"))
                            .andReturn().getResponse().getStatus();
                }));
            }
            go.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(30, TimeUnit.SECONDS));
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }
}
