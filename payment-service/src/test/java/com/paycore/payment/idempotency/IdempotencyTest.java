package com.paycore.payment.idempotency;

import com.paycore.payment.payment.PaymentRequest;
import com.paycore.payment.payment.PaymentStatus;
import com.paycore.payment.support.PaymentServiceTest;
import com.paycore.payment.support.Tokens;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static com.paycore.payment.support.Requests.body;
import static com.paycore.payment.support.Requests.read;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Spec §7. Everything here must hold with Postgres alone. */
class IdempotencyTest extends PaymentServiceTest {

    private static final String BODY = body(1999, "tok_approve");
    private static final String BODY_FINGERPRINT =
            Fingerprint.of(new PaymentRequest(1999L, "USD", "tok_approve", null));
    private static final Duration LEASE = Duration.ofSeconds(30);

    @Autowired
    private IdempotencyKeyStore keys;

    @Test
    void aRepeatedRequestReplaysTheFirstResponse() throws Exception {
        String first = create(Tokens.writer("acme"), "order-42", BODY)
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andReturn().getResponse().getContentAsString();

        String second = create(Tokens.writer("acme"), "order-42", BODY)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn().getResponse().getContentAsString();

        assertThat(second).isEqualTo(first);
        assertThat(paymentCount()).isEqualTo(1);
        assertThat(acquirer.totalAuthorizeCalls()).isEqualTo(1);
    }

    @Test
    void aDeclineIsReplayedToo() throws Exception {
        String declined = body(1999, "tok_decline_funds");
        create(Tokens.writer("acme"), "order-42", declined).andExpect(status().isCreated());

        create(Tokens.writer("acme"), "order-42", declined)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.status").value("DECLINED"));
        assertThat(acquirer.totalAuthorizeCalls()).isEqualTo(1);
    }

    @Test
    void theSameKeyWithADifferentRequestIsRefused() throws Exception {
        create(Tokens.writer("acme"), "order-42", BODY).andExpect(status().isCreated());

        create(Tokens.writer("acme"), "order-42", body(2500, "tok_approve"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.detail").value("Idempotency-Key was already used with a different request"));
        assertThat(paymentCount()).isEqualTo(1);
        assertThat(acquirer.totalAuthorizeCalls()).isEqualTo(1);
    }

    /**
     * Otherwise one tenant could be handed another tenant's payment on "replay". The retries
     * matter as much as the first two requests: once both tenants hold "order-42", a lookup that
     * ignored the tenant would find two rows, or the wrong one.
     */
    @Test
    void keysBelongToATenant() throws Exception {
        String acme = read(create(Tokens.writer("acme"), "order-42", BODY).andExpect(status().isCreated()), "$.id");
        String globex = read(create(Tokens.writer("globex"), "order-42", BODY).andExpect(status().isCreated()), "$.id");

        assertThat(globex).isNotEqualTo(acme);
        assertThat(paymentCount()).isEqualTo(2);

        create(Tokens.writer("acme"), "order-42", BODY)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(acme));
        create(Tokens.writer("globex"), "order-42", BODY)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(globex));
    }

    /** Spec §7.6: a snapshot of the response, not the payment's current state. */
    @Test
    void aReplayIsASnapshotNotTheCurrentState() throws Exception {
        String id = read(create(Tokens.writer("acme"), "order-42", BODY), "$.id");
        mockMvc.perform(post("/payments/{id}/capture", id).with(Tokens.writer("acme"))).andExpect(status().isOk());

        create(Tokens.writer("acme"), "order-42", BODY).andExpect(jsonPath("$.status").value("AUTHORIZED"));
        mockMvc.perform(get("/payments/{id}", id).with(Tokens.reader("acme")))
                .andExpect(jsonPath("$.status").value("CAPTURED"));
    }

    /**
     * The whole point. The acquirer charged the card but answered too late; the retry re-drives
     * the same payment under the same reference, the acquirer recognises it, and the client gets
     * the charge that already happened — one payment, one authorization.
     */
    @Test
    void aTimedOutPaymentIsRecoveredByRetryingWithTheSameKey() throws Exception {
        String slow = body(1999, "tok_timeout");
        ResultActions first = create(Tokens.writer("acme"), "order-7", slow).andExpect(status().isBadGateway());
        String paymentId = read(first, "$.paymentId");

        create(Tokens.writer("acme"), "order-7", slow)
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andExpect(jsonPath("$.id").value(paymentId))
                .andExpect(jsonPath("$.status").value("AUTHORIZED"));

        assertThat(paymentCount()).isEqualTo(1);
        assertThat(acquirer.authorizeCalls(UUID.fromString(paymentId))).isEqualTo(2);
        assertThat(acquirer.authorizationsRecorded()).isEqualTo(1);
    }

    /** A stored 502 would freeze the payment in UNKNOWN for good. */
    @Test
    void aBadGatewayIsNeverStoredAsTheAnswer() throws Exception {
        create(Tokens.writer("acme"), "order-7", body(1999, "tok_timeout")).andExpect(status().isBadGateway());

        Map<String, Object> row = jdbc.sql("""
                        SELECT state, response_status, locked_until <= now() AS released
                        FROM idempotency_keys WHERE tenant = 'acme' AND idempotency_key = 'order-7'""")
                .query().singleRow();
        assertThat(row.get("state")).isEqualTo("IN_PROGRESS");
        assertThat(row.get("response_status")).isNull();
        assertThat(row.get("released")).isEqualTo(true);
    }

    /**
     * The acquirer answered, but recording that answer failed. The card may be charged, so the
     * client must hear exactly what it hears for no answer — its payment id, and to retry with the
     * same key — never a bare 500. The retry then recovers the charge.
     */
    @Test
    void aFailureRecordingTheOutcomeStillTellsTheClientItsPaymentId() throws Exception {
        // On the fresh path the first findById is the one inside Tx 2.
        doThrow(new DataAccessResourceFailureException("db down")).when(paymentRepository).findById(any(UUID.class));

        ResultActions first = create(Tokens.writer("acme"), "order-5", BODY)
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detail").value("acquirer outcome unknown; retry with the same Idempotency-Key"));
        String paymentId = read(first, "$.paymentId");
        assertThat(acquirer.authorizationsRecorded()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT status FROM payments WHERE id = :id")
                .param("id", UUID.fromString(paymentId)).query(String.class).single()).isEqualTo("PENDING");

        Mockito.reset(paymentRepository);

        create(Tokens.writer("acme"), "order-5", BODY)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(paymentId))
                .andExpect(jsonPath("$.status").value("AUTHORIZED"));
        assertThat(paymentCount()).isEqualTo(1);
        assertThat(acquirer.authorizeCalls(UUID.fromString(paymentId))).isEqualTo(2);
        assertThat(acquirer.authorizationsRecorded()).isEqualTo(1);
    }

    @Test
    void aRequestStillHoldingTheKeyMeansTryAgainShortly() throws Exception {
        UUID paymentId = seedPayment("acme", PaymentStatus.PENDING, Instant.now());
        seedKey("acme", "order-9", BODY_FINGERPRINT, paymentId, Instant.now().plusSeconds(30));

        create(Tokens.writer("acme"), "order-9", BODY)
                .andExpect(status().isConflict())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.detail").value("a request with this Idempotency-Key is in progress"));
        assertThat(acquirer.totalAuthorizeCalls()).isZero();
    }

    @Test
    void aMismatchIsRefusedEvenWhileTheKeyIsHeld() throws Exception {
        UUID paymentId = seedPayment("acme", PaymentStatus.PENDING, Instant.now());
        seedKey("acme", "order-9", BODY_FINGERPRINT, paymentId, Instant.now().plusSeconds(30));

        create(Tokens.writer("acme"), "order-9", body(2500, "tok_approve")).andExpect(status().is(422));
    }

    /** The process died between committing PENDING and recording the outcome. */
    @Test
    void aPaymentLeftPendingByACrashIsReDriven() throws Exception {
        UUID paymentId = seedPayment("acme", PaymentStatus.PENDING, Instant.now().minusSeconds(60));
        seedKey("acme", "order-9", BODY_FINGERPRINT, paymentId, Instant.now().minusSeconds(30));

        create(Tokens.writer("acme"), "order-9", BODY)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(paymentId.toString()))
                .andExpect(jsonPath("$.status").value("AUTHORIZED"));
        assertThat(paymentCount()).isEqualTo(1);
        assertThat(acquirer.authorizeCalls(paymentId)).isEqualTo(1);
    }

    /**
     * Spec §7.5, at the store: the claim itself re-checks the lease, so a retry that read the key
     * as lapsed but lost the race to claim it does not re-drive too. Through HTTP a live lease is
     * refused before the claim is ever attempted, which is why this is tested here.
     */
    @Test
    void aLapsedLeaseIsClaimedOnceAndALiveOneNever() {
        UUID lapsed = seedPayment("acme", PaymentStatus.PENDING, Instant.now());
        seedKey("acme", "order-9", BODY_FINGERPRINT, lapsed, Instant.now().minusSeconds(30));
        UUID held = seedPayment("acme", PaymentStatus.PENDING, Instant.now());
        seedKey("acme", "order-10", BODY_FINGERPRINT, held, Instant.now().plusSeconds(30));

        assertThat(keys.claimExpired("acme", "order-9", LEASE)).isTrue();
        assertThat(keys.claimExpired("acme", "order-9", LEASE)).isFalse();
        assertThat(keys.claimExpired("acme", "order-10", LEASE)).isFalse();
    }

    @Test
    void aCompletedKeyIsNeverClaimed() {
        UUID paymentId = seedPayment("acme", PaymentStatus.AUTHORIZED, Instant.now());
        seedKey("acme", "order-9", BODY_FINGERPRINT, paymentId, Instant.now().minusSeconds(30));
        keys.complete("acme", "order-9", 201, "{}");

        assertThat(keys.claimExpired("acme", "order-9", LEASE)).isFalse();
    }
}
