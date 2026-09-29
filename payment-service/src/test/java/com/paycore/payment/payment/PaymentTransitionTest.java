package com.paycore.payment.payment;

import com.paycore.payment.support.PaymentServiceTest;
import com.paycore.payment.support.Tokens;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.paycore.payment.support.Requests.body;
import static com.paycore.payment.support.Requests.read;
import static org.assertj.core.api.Assertions.assertThat;
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
    void anotherTenantsPaymentIsNotFound() throws Exception {
        String id = authorized();

        mockMvc.perform(post("/payments/{id}/capture", id).with(Tokens.writer("globex")))
                .andExpect(status().isNotFound());
        assertThat(acquirer.captureCalls()).isZero();
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
        jdbc.sql("UPDATE payments SET transition_started_at = now() - interval '31 seconds' WHERE id = :id")
                .param("id", UUID.fromString(id))
                .update();

        capture(id).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CAPTURED"));
        assertThat(acquirer.captureCalls()).isEqualTo(2);
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
