package com.paycore.acquirer;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
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

    /**
     * {@code move()}'s atomicity rests entirely on {@code compute} — a get/check/put rewrite
     * would still pass every sequential test in this file. Racing capture against void on the
     * same, already-approved authorization, over and over, is what actually exercises that:
     * with a non-atomic move, both racers can observe the APPROVED state and both win.
     */
    @Test
    void concurrentCaptureAndVoidOnOneAuthorizationHasExactlyOneWinner() throws Exception {
        int iterations = 1000;
        AuthorizationBook book = new AuthorizationBook();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int iteration = 0; iteration < iterations; iteration++) {
                String reference = "ref-race-" + iteration;
                AuthorizationRequest request = new AuthorizationRequest(reference, 1999, "USD", "tok_approve");
                String id = book.authorize(request).authorization().authorizationId();

                CyclicBarrier barrier = new CyclicBarrier(2);
                Future<Authorization> captureCall = executor.submit(() -> {
                    barrier.await();
                    return book.capture(id);
                });
                Future<Authorization> voidCall = executor.submit(() -> {
                    barrier.await();
                    return book.voidAuthorization(id);
                });

                Authorization captured = null;
                ResponseStatusException captureFailure = null;
                try {
                    captured = captureCall.get(5, TimeUnit.SECONDS);
                } catch (ExecutionException e) {
                    captureFailure = (ResponseStatusException) e.getCause();
                }

                Authorization voided = null;
                ResponseStatusException voidFailure = null;
                try {
                    voided = voidCall.get(5, TimeUnit.SECONDS);
                } catch (ExecutionException e) {
                    voidFailure = (ResponseStatusException) e.getCause();
                }

                boolean captureWon = captured != null;
                boolean voidWon = voided != null;
                assertThat(captureWon ^ voidWon).as("iteration %d: exactly one winner", iteration).isTrue();

                ResponseStatusException loserFailure = captureWon ? voidFailure : captureFailure;
                assertThat(loserFailure).as("iteration %d", iteration).isNotNull();
                assertThat(loserFailure.getStatusCode().value()).as("iteration %d", iteration).isEqualTo(409);

                AuthorizationStatus expectedStatus =
                        captureWon ? AuthorizationStatus.CAPTURED : AuthorizationStatus.VOIDED;
                Authorization replay = captureWon ? book.capture(id) : book.voidAuthorization(id);
                assertThat(replay.status()).as("iteration %d", iteration).isEqualTo(expectedStatus);
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
