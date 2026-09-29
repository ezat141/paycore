package com.paycore.payment.payment;

import com.paycore.payment.support.PaymentServiceTest;
import com.paycore.payment.support.Requests;
import com.paycore.payment.support.Tokens;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.UUID;

import static com.paycore.payment.support.Requests.body;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A client's {@code Accept} header must never change whether the acquirer gets called, and every
 * error — including one content negotiation itself raises — always renders in the platform's JSON
 * shape (spec §9). This was found on real Tomcat, not MockMvc's own dispatch: an unsatisfiable
 * {@code Accept} let the handler run to completion (charging the card) before failing to render
 * the response, and the resulting error had no preset content type, so it, too, failed negotiation
 * and came back as an empty body instead of the platform shape.
 */
class PaymentContentNegotiationTest extends PaymentServiceTest {

    @Test
    void anUnacceptableAcceptRefusesBeforeTheAcquirerIsCalled() throws Exception {
        mockMvc.perform(Requests.createPayment(Tokens.writer("acme"), "order-1", body(1999, "tok_approve"))
                        .accept(MediaType.TEXT_PLAIN))
                .andExpect(status().isNotAcceptable());

        assertThat(acquirer.totalAuthorizeCalls()).isZero();
        assertThat(paymentCount()).isZero();
    }

    /** Not asserted as 404 specifically: an unacceptable Accept may itself answer 406 first. */
    @Test
    void anUnacceptableAcceptOnGetStillRendersThePlatformShape() throws Exception {
        mockMvc.perform(get("/payments/{id}", UUID.randomUUID())
                        .with(Tokens.reader("acme"))
                        .accept(MediaType.TEXT_PLAIN))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isBetween(400, 499))
                .andExpect(jsonPath("$.status").exists());
    }

    /** Regression guard: the produces declaration must not change the no-Accept-header path. */
    @Test
    void aTimeoutWithNoAcceptHeaderStillAnswersBadGatewayWithThePaymentId() throws Exception {
        create(Tokens.writer("acme"), "order-1", body(1999, "tok_timeout"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.paymentId").isNotEmpty());
    }
}
