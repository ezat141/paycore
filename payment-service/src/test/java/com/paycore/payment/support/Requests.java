package com.paycore.payment.support;

import com.jayway.jsonpath.JsonPath;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Static helpers, so tests that cannot extend PaymentServiceTest can use them too. */
public final class Requests {

    private Requests() {
    }

    public static String body(long amount, String cardToken) {
        return """
                {"amount":%d,"currency":"USD","cardToken":"%s"}""".formatted(amount, cardToken);
    }

    public static MockHttpServletRequestBuilder createPayment(RequestPostProcessor token, String idempotencyKey, String body) {
        return post("/payments")
                .with(token)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    public static String read(ResultActions result, String jsonPath) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), jsonPath);
    }
}
