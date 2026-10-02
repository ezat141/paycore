package com.paycore.payment.payment;

import com.paycore.payment.support.PaymentServiceTest;
import com.paycore.payment.support.Tokens;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.paycore.payment.support.Requests.body;
import static com.paycore.payment.support.Requests.read;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PaymentTransitionTest extends PaymentServiceTest {

    @Test
    void captureMovesAnAuthorizedPaymentToCaptured() throws Exception {
        String id = authorized();

        capture(id).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CAPTURED"));
        assertThat(acquirer.captureCalls()).isEqualTo(1);
    }

    @Test
    void repeatingACompletedCaptureDoesNotCallTheAcquirerAgain() throws Exception {
        String id = authorized();
        capture(id).andExpect(status().isOk());

        capture(id).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CAPTURED"));
        assertThat(acquirer.captureCalls()).isEqualTo(1);
    }

    @Test
    void voidMovesToVoidedAndRepeatingItIsHarmless() throws Exception {
        String id = authorized();

        voidIt(id).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("VOIDED"));
        voidIt(id).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("VOIDED"));
        assertThat(acquirer.voidCalls()).isEqualTo(1);
    }

    @Test
    void aVoidedPaymentCannotBeCaptured() throws Exception {
        String id = authorized();
        voidIt(id).andExpect(status().isOk());

        capture(id).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("cannot capture a payment that is VOIDED"));
    }

    @Test
    void aCapturedPaymentCannotBeVoided() throws Exception {
        String id = authorized();
        capture(id).andExpect(status().isOk());

        voidIt(id).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("cannot void a payment that is CAPTURED"));
    }

    @Test
    void aDeclinedPaymentCannotBeCaptured() throws Exception {
        String id = read(create(Tokens.writer("acme"), UUID.randomUUID().toString(), body(1999, "tok_decline_funds")), "$.id");

        capture(id).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("cannot capture a payment that is DECLINED"));
        assertThat(acquirer.captureCalls()).isZero();
    }

    @Test
    void aDeclinedPaymentCannotBeVoided() throws Exception {
        String id = read(create(Tokens.writer("acme"), UUID.randomUUID().toString(), body(1999, "tok_decline_funds")), "$.id");

        voidIt(id).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("cannot void a payment that is DECLINED"));
        assertThat(acquirer.voidCalls()).isZero();
    }

    @Test
    void anotherTenantsPaymentIsNotFound() throws Exception {
        String id = authorized();

        mockMvc.perform(post("/payments/{id}/capture", id).with(Tokens.writer("globex")))
                .andExpect(status().isNotFound());
        assertThat(acquirer.captureCalls()).isZero();
    }

    @Test
    void anotherTenantCannotVoidIt() throws Exception {
        String id = authorized();

        mockMvc.perform(post("/payments/{id}/void", id).with(Tokens.writer("globex")))
                .andExpect(status().isNotFound());
        assertThat(acquirer.voidCalls()).isZero();
    }

    /** Never reverted to AUTHORIZED: the acquirer may have captured it. */
    @Test
    void aCaptureTheAcquirerDidNotAnswerStaysInFlight() throws Exception {
        String id = authorized();
        acquirer.failNextActions(1);

        capture(id).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detail").value("acquirer outcome unknown; retry the capture"))
                .andExpect(jsonPath("$.paymentId").doesNotExist());
        mockMvc.perform(get("/payments/{id}", id).with(Tokens.reader("acme")))
                .andExpect(jsonPath("$.status").value("CAPTURING"));
    }

    @Test
    void anImmediateRetryIsToldTheCaptureIsInProgress() throws Exception {
        String id = authorized();
        acquirer.failNextActions(1);
        capture(id).andExpect(status().isBadGateway());

        capture(id).andExpect(status().isConflict())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.detail").value("capture in progress"));
        assertThat(acquirer.captureCalls()).isEqualTo(1);
    }

    /** Safe because the acquirer's capture is idempotent on the authorization id. */
    @Test
    void aStaleCaptureIsReDriven() throws Exception {
        String id = authorized();
        acquirer.failNextActions(1);
        capture(id).andExpect(status().isBadGateway());
        // An hour, not 31 seconds: comparing Postgres's clock to the JVM's needs more than a 1s margin.
        jdbc.sql("UPDATE payments SET transition_started_at = now() - interval '1 hour' WHERE id = :id")
                .param("id", UUID.fromString(id))
                .update();

        capture(id).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CAPTURED"));
        assertThat(acquirer.captureCalls()).isEqualTo(2);
    }

    /** Mirrors aCaptureTheAcquirerDidNotAnswerStaysInFlight + anImmediateRetryIsToldTheCaptureIsInProgress + aStaleCaptureIsReDriven, for void. */
    @Test
    void aVoidTheAcquirerDidNotAnswerStaysVoidingAndIsReDrivenWhenStale() throws Exception {
        String id = authorized();
        acquirer.failNextActions(1);

        voidIt(id).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detail").value("acquirer outcome unknown; retry the void"));

        voidIt(id).andExpect(status().isConflict())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.detail").value("void in progress"));
        assertThat(acquirer.voidCalls()).isEqualTo(1);

        // An hour, not 31 seconds: comparing Postgres's clock to the JVM's needs more than a 1s margin.
        jdbc.sql("UPDATE payments SET transition_started_at = now() - interval '1 hour' WHERE id = :id")
                .param("id", UUID.fromString(id))
                .update();

        voidIt(id).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("VOIDED"));
        assertThat(acquirer.voidCalls()).isEqualTo(2);
    }

    /**
     * The acquirer captured, and recording it failed. Like a capture it did not answer: 502, the
     * payment left CAPTURING for the stale re-drive, never a bare 500.
     */
    @Test
    void aCaptureWhoseOutcomeFailsToRecordIsABadGatewayAndStaysInFlight() throws Exception {
        String id = authorized();
        doThrow(new DataAccessResourceFailureException("db down"))
                .when(paymentWrites).completeTransition(any(UUID.class), any(TransitionAction.class));

        capture(id).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detail").value("acquirer outcome unknown; retry the capture"))
                .andExpect(jsonPath("$.paymentId").doesNotExist());
        assertThat(acquirer.captureCalls()).isEqualTo(1);
        mockMvc.perform(get("/payments/{id}", id).with(Tokens.reader("acme")))
                .andExpect(jsonPath("$.status").value("CAPTURING"));
    }

    /**
     * Reviewer's probe (task-11 review): an acquirer 4xx used to bubble up as a bare 500 and leave
     * the payment stuck. {@code acquirer.reset()} makes the fake forget the authorization id, so
     * its capture/void handler answers 404 — the same shape as a real acquirer refusing outright.
     */
    @Test
    void anAcquirerRefusalLeavesThePaymentInFlightAndSaysWhy() throws Exception {
        String id = authorized();
        acquirer.reset();

        capture(id).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detail").value("acquirer refused the capture; the payment needs reconciliation"));
        mockMvc.perform(get("/payments/{id}", id).with(Tokens.reader("acme")))
                .andExpect(jsonPath("$.status").value("CAPTURING"));
    }

    /**
     * Forces the interleaving the {@code @RepeatedTest} race relies on luck for: a barrier inside
     * the claim transaction holds both requests until each has loaded the payment, so both commit
     * from the same version and exactly one can win. The loser's 409 detail proves {@code @Version}
     * decided it, not the earlier status check (which would also see AUTHORIZED for both).
     */
    @Test
    void aRacingClaimIsDecidedByTheVersionCheck() throws Exception {
        String id = authorized();
        CyclicBarrier bothLoaded = new CyclicBarrier(2);
        doAnswer(inv -> {
            Object loaded = Mockito.mockingDetails(paymentRepository).getMockCreationSettings()
                    .getDefaultAnswer().answer(inv);
            bothLoaded.await(5, TimeUnit.SECONDS);
            return loaded;
        }).when(paymentRepository).findByIdAndTenant(any(UUID.class), anyString());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ResultActions> captureFuture = pool.submit(() -> capture(id));
            Future<ResultActions> voidFuture = pool.submit(() -> voidIt(id));

            ResultActions captureResult = captureFuture.get(10, TimeUnit.SECONDS);
            ResultActions voidResult = voidFuture.get(10, TimeUnit.SECONDS);
            int captureStatus = captureResult.andReturn().getResponse().getStatus();
            int voidStatus = voidResult.andReturn().getResponse().getStatus();

            assertThat(List.of(captureStatus, voidStatus)).containsExactlyInAnyOrder(200, 409);
            String loserBody = captureStatus == 409
                    ? captureResult.andReturn().getResponse().getContentAsString()
                    : voidResult.andReturn().getResponse().getContentAsString();
            assertThat(loserBody).contains("payment was modified concurrently; retry");
            assertThat(acquirer.captureCalls() + acquirer.voidCalls()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    /** The claim is taken in the database first, so the loser never reaches the acquirer. */
    @RepeatedTest(10)
    void aRacingCaptureAndVoidProduceExactlyOneWinner() throws Exception {
        String id = authorized();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Future<Integer> capture = pool.submit(() -> {
                go.await();
                return capture(id).andReturn().getResponse().getStatus();
            });
            Future<Integer> voidResult = pool.submit(() -> {
                go.await();
                return voidIt(id).andReturn().getResponse().getStatus();
            });
            go.countDown();

            List<Integer> statuses = List.of(capture.get(10, TimeUnit.SECONDS), voidResult.get(10, TimeUnit.SECONDS));

            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
            assertThat(acquirer.captureCalls() + acquirer.voidCalls()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    private String authorized() throws Exception {
        return read(create(Tokens.writer("acme"), UUID.randomUUID().toString(), body(1999, "tok_approve"))
                .andExpect(status().isCreated()), "$.id");
    }

    private ResultActions capture(String id) throws Exception {
        return mockMvc.perform(post("/payments/{id}/capture", id).with(Tokens.writer("acme")));
    }

    private ResultActions voidIt(String id) throws Exception {
        return mockMvc.perform(post("/payments/{id}/void", id).with(Tokens.writer("acme")));
    }
}
