package com.paycore.acquirer;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A plain unit test — no Spring context — for the guarantee {@link AuthorizationBook} exists to
 * provide: two callers racing on the same reference must not both create an authorization.
 */
class AuthorizationBookTest {

    @Test
    void concurrentIdenticalRequestsCreateExactlyOneAuthorization() throws Exception {
        int threadCount = 16;
        int iterations = 200;
        AuthorizationBook book = new AuthorizationBook();
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        try {
            // A broken get-then-put (as opposed to computeIfAbsent's atomic check-and-set) would
            // only sometimes lose the race on a single reference, so repeat it over many fresh
            // references — reusing the same pool — to make a real bug fail on nearly every run.
            for (int iteration = 0; iteration < iterations; iteration++) {
                String reference = "ref-concurrent-" + iteration;
                AuthorizationRequest request = new AuthorizationRequest(reference, 1999, "USD", "tok_approve");

                CountDownLatch ready = new CountDownLatch(threadCount);
                CountDownLatch start = new CountDownLatch(1);
                List<Future<AuthorizationBook.Recorded>> futures = new ArrayList<>();
                for (int i = 0; i < threadCount; i++) {
                    futures.add(executor.submit(() -> {
                        ready.countDown();
                        await(start);
                        return book.authorize(request);
                    }));
                }
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
                start.countDown();

                List<AuthorizationBook.Recorded> results = new ArrayList<>();
                for (Future<AuthorizationBook.Recorded> future : futures) {
                    results.add(future.get(5, TimeUnit.SECONDS));
                }

                assertThat(results).as("iteration %d", iteration).hasSize(threadCount);

                Set<String> distinctIds = results.stream()
                        .map(recorded -> recorded.authorization().authorizationId())
                        .collect(Collectors.toSet());
                assertThat(distinctIds).as("iteration %d", iteration).hasSize(1);

                long createdCount = results.stream().filter(AuthorizationBook.Recorded::created).count();
                assertThat(createdCount).as("iteration %d", iteration).isEqualTo(1);
            }
        } finally {
            executor.shutdown();
        }
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
