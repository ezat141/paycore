package com.paycore.acquirer;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A plain unit test — no Spring context — for the guarantee {@link AuthorizationBook} exists to
 * provide: two callers racing on the same reference must not both create an authorization.
 */
class AuthorizationBookTest {

    @Test
    void concurrentIdenticalRequestsCreateExactlyOneAuthorization() throws InterruptedException {
        int threadCount = 16;
        AuthorizationBook book = new AuthorizationBook();
        AuthorizationRequest request = new AuthorizationRequest("ref-concurrent", 1999, "USD", "tok_approve");

        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        List<AuthorizationBook.Recorded> results = new CopyOnWriteArrayList<>();
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        try {
            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    ready.countDown();
                    await(start);
                    results.add(book.authorize(request));
                });
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
        } finally {
            executor.shutdown();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(results).hasSize(threadCount);

        Set<String> distinctIds = results.stream()
                .map(recorded -> recorded.authorization().authorizationId())
                .collect(Collectors.toSet());
        assertThat(distinctIds).hasSize(1);

        long createdCount = results.stream().filter(AuthorizationBook.Recorded::created).count();
        assertThat(createdCount).isEqualTo(1);
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
