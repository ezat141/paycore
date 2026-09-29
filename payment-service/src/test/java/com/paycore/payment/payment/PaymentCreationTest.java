package com.paycore.payment.payment;

import com.paycore.payment.support.PaymentServiceTest;
import com.paycore.payment.support.Tokens;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static com.paycore.payment.support.Requests.body;
import static com.paycore.payment.support.Requests.read;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PaymentCreationTest extends PaymentServiceTest {

    @Test
    void anApprovedCardIsAuthorized() throws Exception {
        create(Tokens.writer("acme"), "order-1", """
                {"amount":1999,"currency":"USD","cardToken":"tok_approve","description":"Order 42"}""")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.status").value("AUTHORIZED"))
                .andExpect(jsonPath("$.amount").value(1999))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.description").value("Order 42"))
                .andExpect(jsonPath("$.declineCode").value(nullValue()))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.updatedAt").isNotEmpty())
                .andExpect(jsonPath("$.cardToken").doesNotExist())
                .andExpect(jsonPath("$.tenant").doesNotExist())
                .andExpect(jsonPath("$.acquirerAuthorizationId").doesNotExist());
    }

    /** A decline is a definite answer about a payment that exists: 201, not an HTTP failure. */
    @Test
    void aDeclineIsStillACreatedPayment() throws Exception {
        create(Tokens.writer("acme"), "order-1", body(1999, "tok_decline_funds"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DECLINED"))
                .andExpect(jsonPath("$.declineCode").value("insufficient_funds"));
    }

    @Test
    void thePaymentIdIsTheAcquirersReference() throws Exception {
        String id = read(create(Tokens.writer("acme"), "order-1", body(1999, "tok_approve")), "$.id");

        assertThat(acquirer.authorizeCalls(UUID.fromString(id))).isEqualTo(1);
    }

    /** No answer is not a failure: the card may have been charged. */
    @Test
    void aTimeoutLeavesThePaymentUnknown() throws Exception {
        ResultActions result = create(Tokens.writer("acme"), "order-1", body(1999, "tok_timeout"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("bad_gateway"))
                .andExpect(jsonPath("$.detail").value("acquirer outcome unknown; retry with the same Idempotency-Key"))
                .andExpect(jsonPath("$.paymentId").isNotEmpty());

        assertThat(statusOf(read(result, "$.paymentId"))).isEqualTo("UNKNOWN");
    }

    /**
     * Spec §5.3: no transaction may span the acquirer call. The hook runs inside the acquirer's
     * own handler, on a different thread, and reads through {@code jdbc}'s own pooled connection —
     * so it only ever sees what has actually been committed, never a transaction still open on the
     * request thread. If a transaction were held open, the row would not be visible yet at all;
     * that is caught explicitly, rather than left to surface as some other exception, so this test
     * fails on the assertion it is about, not on an incidental side effect of the row being absent.
     */
    @Test
    void thePendingRowIsCommittedBeforeTheAcquirerIsCalled() throws Exception {
        AtomicReference<String> statusDuringAuthorize = new AtomicReference<>("(query never ran)");
        acquirer.onAuthorize(reference -> {
            try {
                statusDuringAuthorize.set(jdbc.sql("SELECT status FROM payments WHERE id = ?::uuid")
                        .param(reference)
                        .query(String.class)
                        .single());
            } catch (EmptyResultDataAccessException rowNotCommittedYet) {
                statusDuringAuthorize.set("(no such row yet)");
            }
        });

        create(Tokens.writer("acme"), "order-1", body(1999, "tok_approve"));

        assertThat(statusDuringAuthorize.get()).isEqualTo("PENDING");
    }

    @Test
    void anAcquirerErrorAlsoLeavesThePaymentUnknown() throws Exception {
        ResultActions result = create(Tokens.writer("acme"), "order-1", body(1999, "tok_error"))
                .andExpect(status().isBadGateway());

        assertThat(statusOf(read(result, "$.paymentId"))).isEqualTo("UNKNOWN");
    }

    @Test
    void theOwningTenantCanReadIt() throws Exception {
        String id = read(create(Tokens.writer("acme"), "order-1", body(1999, "tok_approve")), "$.id");

        mockMvc.perform(get("/payments/{id}", id).with(Tokens.reader("acme")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("AUTHORIZED"));
    }

    /** 404, not 403: a refusal would confirm the payment exists. */
    @Test
    void anotherTenantsPaymentIsNotFound() throws Exception {
        String id = read(create(Tokens.writer("acme"), "order-1", body(1999, "tok_approve")), "$.id");

        mockMvc.perform(get("/payments/{id}", id).with(Tokens.reader("globex")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"))
                .andExpect(jsonPath("$.detail").value("no such payment"));
    }

    @Test
    void anUnknownIdIsNotFound() throws Exception {
        mockMvc.perform(get("/payments/{id}", UUID.randomUUID()).with(Tokens.reader("acme")))
                .andExpect(status().isNotFound());
    }

    @Test
    void aMalformedIdIsABadRequest() throws Exception {
        mockMvc.perform(get("/payments/not-a-uuid").with(Tokens.reader("acme")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("malformed id"));
    }

    private String statusOf(String paymentId) throws Exception {
        return read(mockMvc.perform(get("/payments/{id}", paymentId).with(Tokens.reader("acme"))), "$.status");
    }
}
