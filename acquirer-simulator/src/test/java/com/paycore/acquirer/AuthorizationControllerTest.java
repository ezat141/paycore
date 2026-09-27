package com.paycore.acquirer;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "simulator.slow-response=1s")
@AutoConfigureMockMvc
class AuthorizationControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    AuthorizationBook book;

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

    /** A repeated reference replays whatever status was actually stored, not just APPROVED. */
    @Test
    void aRepeatedReferenceForADeclinedAuthorizationReturnsTheStoredDecline() throws Exception {
        String reference = UUID.randomUUID().toString();
        String first = authorizationId(authorize(reference, 1999, "tok_decline_funds")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DECLINED")));

        authorize(reference, 1999, "tok_decline_funds")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DECLINED"))
                .andExpect(jsonPath("$.declineCode").value("insufficient_funds"))
                .andExpect(jsonPath("$.authorizationId").value(first));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("conflictingDetails")
    void aRepeatedReferenceWithDifferentDetailsIsAConflict(
            String label, long amount, String currency, String cardToken) throws Exception {
        String reference = UUID.randomUUID().toString();
        authorize(reference, 1999, "USD", "tok_approve").andExpect(status().isOk());

        authorize(reference, amount, currency, cardToken).andExpect(status().isConflict());
    }

    private static Stream<Arguments> conflictingDetails() {
        return Stream.of(
                Arguments.of("different amount", 2500L, "USD", "tok_approve"),
                Arguments.of("different currency", 1999L, "EUR", "tok_approve"),
                Arguments.of("different cardToken", 1999L, "USD", "tok_decline_funds"));
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

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void aBlankReferenceIsABadRequest(String reference) throws Exception {
        authorize(reference, 1999, "tok_approve").andExpect(status().isBadRequest());
    }

    @Test
    void aMissingReferenceFieldIsABadRequest() throws Exception {
        mockMvc.perform(post("/authorizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":1999,"currency":"USD","cardToken":"tok_approve"}"""))
                .andExpect(status().isBadRequest());
    }

    /**
     * The charge happens even though the response is late, and a retry sent <em>while the first
     * call is still stalled</em> must still find it immediately — that's what lets PayCore
     * recover a timed-out payment without charging twice. Running the two calls one after
     * another (as opposed to overlapping them) wouldn't tell a real in-flight retry apart from
     * one that simply waited for the first call to finish, so the first call runs on its own
     * thread and the retry is sent before it returns.
     */
    @Test
    void aTimeoutTokenRecordsTheApprovalBeforeStalling() throws Exception {
        String reference = UUID.randomUUID().toString();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            long started = System.nanoTime();
            Future<String> firstCall = executor.submit(() -> authorizationId(
                    authorize(reference, 1999, "tok_timeout").andExpect(jsonPath("$.status").value("APPROVED"))));

            // Wait on AuthorizationBook directly rather than probing over HTTP: a probe request
            // for the same reference would itself race computeIfAbsent and could end up being
            // the one that creates the entry, corrupting the very authorization under test.
            awaitRecorded(reference);

            long retryStarted = System.nanoTime();
            String second = authorizationId(authorize(reference, 1999, "tok_timeout")
                    .andExpect(jsonPath("$.status").value("APPROVED")));
            long secondMillis = (System.nanoTime() - retryStarted) / 1_000_000;

            String first = firstCall.get(5, TimeUnit.SECONDS);
            long firstMillis = (System.nanoTime() - started) / 1_000_000;

            assertThat(firstMillis).isGreaterThanOrEqualTo(1000);
            assertThat(secondMillis).isLessThan(500);
            assertThat(second).isEqualTo(first);
        } finally {
            executor.shutdown();
        }
    }

    private void awaitRecorded(String reference) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!book.isRecorded(reference)) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("authorization for reference " + reference + " was never recorded");
            }
            Thread.sleep(5);
        }
    }

    private ResultActions authorize(String reference, long amount, String cardToken) throws Exception {
        return authorize(reference, amount, "USD", cardToken);
    }

    private ResultActions authorize(String reference, long amount, String currency, String cardToken)
            throws Exception {
        return mockMvc.perform(post("/authorizations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"reference":"%s","amount":%d,"currency":"%s","cardToken":"%s"}"""
                        .formatted(reference, amount, currency, cardToken)));
    }

    private static String authorizationId(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.authorizationId");
    }
}
