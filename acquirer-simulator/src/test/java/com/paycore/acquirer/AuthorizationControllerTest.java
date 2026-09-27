package com.paycore.acquirer;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "simulator.slow-response=400ms")
@AutoConfigureMockMvc
class AuthorizationControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void approvesAnApprovingToken() throws Exception {
        authorize(UUID.randomUUID().toString(), 1999, "tok_approve")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorizationId").value(startsWith("auth_")))
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.declineCode").value(nullValue()));
    }

    @Test
    void anUnrecognisedTokenIsApproved() throws Exception {
        authorize(UUID.randomUUID().toString(), 1999, "tok_anything_else")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    void declinesForInsufficientFunds() throws Exception {
        authorize(UUID.randomUUID().toString(), 1999, "tok_decline_funds")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DECLINED"))
                .andExpect(jsonPath("$.declineCode").value("insufficient_funds"));
    }

    /** The property PayCore's safe retry rests on: a known reference is not a new charge. */
    @Test
    void aRepeatedReferenceReturnsTheStoredAuthorization() throws Exception {
        String reference = UUID.randomUUID().toString();
        String first = authorizationId(authorize(reference, 1999, "tok_approve"));
        String second = authorizationId(authorize(reference, 1999, "tok_approve"));

        assertThat(second).isEqualTo(first);
    }

    @Test
    void aRepeatedReferenceWithDifferentDetailsIsAConflict() throws Exception {
        String reference = UUID.randomUUID().toString();
        authorize(reference, 1999, "tok_approve").andExpect(status().isOk());

        authorize(reference, 2500, "tok_approve").andExpect(status().isConflict());
    }

    /** A 500 records nothing, so the same reference can still be authorized afterwards. */
    @Test
    void anErrorTokenFailsAndRecordsNothing() throws Exception {
        String reference = UUID.randomUUID().toString();
        authorize(reference, 1999, "tok_error").andExpect(status().isInternalServerError());

        authorize(reference, 1999, "tok_approve")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    /**
     * The charge happens even though the response is late. A retry with the same reference
     * finds it immediately — which is what lets PayCore recover a timed-out payment without
     * charging twice.
     */
    @Test
    void aTimeoutTokenRecordsTheApprovalBeforeStalling() throws Exception {
        String reference = UUID.randomUUID().toString();

        long started = System.nanoTime();
        String first = authorizationId(authorize(reference, 1999, "tok_timeout")
                .andExpect(jsonPath("$.status").value("APPROVED")));
        long firstMillis = (System.nanoTime() - started) / 1_000_000;

        started = System.nanoTime();
        String second = authorizationId(authorize(reference, 1999, "tok_timeout"));
        long secondMillis = (System.nanoTime() - started) / 1_000_000;

        assertThat(firstMillis).isGreaterThanOrEqualTo(400);
        assertThat(secondMillis).isLessThan(400);
        assertThat(second).isEqualTo(first);
    }

    private ResultActions authorize(String reference, long amount, String cardToken) throws Exception {
        return mockMvc.perform(post("/authorizations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"reference":"%s","amount":%d,"currency":"USD","cardToken":"%s"}"""
                        .formatted(reference, amount, cardToken)));
    }

    private static String authorizationId(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.authorizationId");
    }
}
