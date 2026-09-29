package com.paycore.payment.payment;

import com.paycore.payment.support.PaymentServiceTest;
import com.paycore.payment.support.Tokens;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;

import java.util.UUID;

import static com.paycore.payment.support.Requests.body;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Every refusal here happens before the acquirer is called. */
class PaymentValidationTest extends PaymentServiceTest {

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            amount zero            | {"amount":0,"currency":"USD","cardToken":"tok_approve"}           | amount:
            amount too large       | {"amount":1000000001,"currency":"USD","cardToken":"tok_approve"}  | amount:
            amount missing         | {"currency":"USD","cardToken":"tok_approve"}                      | amount:
            currency lowercase     | {"amount":1999,"currency":"usd","cardToken":"tok_approve"}        | currency:
            currency unsupported   | {"amount":1999,"currency":"JPY","cardToken":"tok_approve"}        | currency:
            currency missing       | {"amount":1999,"cardToken":"tok_approve"}                         | currency:
            card number, not token | {"amount":1999,"currency":"USD","cardToken":"4111111111111111"}   | cardToken:
            card token missing     | {"amount":1999,"currency":"USD"}                                  | cardToken:
            """)
    void anInvalidFieldIsRefusedByName(String name, String requestBody, String field) throws Exception {
        create(Tokens.writer("acme"), UUID.randomUUID().toString(), requestBody)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString(field)));

        assertThat(acquirer.totalAuthorizeCalls()).isZero();
    }

    @Test
    void aDescriptionOver255CharactersIsRefused() throws Exception {
        String requestBody = """
                {"amount":1999,"currency":"USD","cardToken":"tok_approve","description":"%s"}"""
                .formatted("x".repeat(256));

        create(Tokens.writer("acme"), "order-1", requestBody)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("description:")));
    }

    @Test
    void aFractionalAmountIsRefusedNotTruncated() throws Exception {
        create(Tokens.writer("acme"), "order-1", """
                {"amount":19.99,"currency":"USD","cardToken":"tok_approve"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("malformed request body"));

        assertThat(acquirer.totalAuthorizeCalls()).isZero();
    }

    @Test
    void anUnknownFieldIsRefused() throws Exception {
        create(Tokens.writer("acme"), "order-1", """
                {"amount":1999,"currency":"USD","cardToken":"tok_approve","amout":5}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("unknown field: amout"));
    }

    @Test
    void malformedJsonIsRefused() throws Exception {
        create(Tokens.writer("acme"), "order-1", "{ not json")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("malformed request body"));
    }

    @Test
    void aMissingIdempotencyKeyIsRefused() throws Exception {
        mockMvc.perform(post("/payments").with(Tokens.writer("acme"))
                        .contentType(MediaType.APPLICATION_JSON).content(body(1999, "tok_approve")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Idempotency-Key header is required"));
    }

    @Test
    void anIdempotencyKeyWithASpaceIsRefused() throws Exception {
        create(Tokens.writer("acme"), "order 1", body(1999, "tok_approve"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Idempotency-Key must be 1-255 printable ASCII characters, without spaces"));
    }

    @Test
    void anOverlongIdempotencyKeyIsRefused() throws Exception {
        create(Tokens.writer("acme"), "k".repeat(256), body(1999, "tok_approve"))
                .andExpect(status().isBadRequest());
    }
}
