package com.paycore.payment.payment;

import com.paycore.payment.support.PaymentServiceTest;
import com.paycore.payment.support.Tokens;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PaymentListTest extends PaymentServiceTest {

    private static final Instant T0 = Instant.parse("2026-09-01T10:00:00Z");

    @Test
    void newestFirstAndOnlyTheCallersTenant() throws Exception {
        UUID older = seedPayment("acme", PaymentStatus.AUTHORIZED, T0);
        UUID newer = seedPayment("acme", PaymentStatus.CAPTURED, T0.plusSeconds(60));
        seedPayment("globex", PaymentStatus.AUTHORIZED, T0.plusSeconds(120));

        mockMvc.perform(get("/payments").with(Tokens.reader("acme")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(newer.toString()))
                .andExpect(jsonPath("$[1].id").value(older.toString()));
    }

    @Test
    void theLimitIsRespected() throws Exception {
        for (int i = 0; i < 3; i++) {
            seedPayment("acme", PaymentStatus.AUTHORIZED, T0.plusSeconds(i));
        }

        mockMvc.perform(get("/payments").param("limit", "2").with(Tokens.reader("acme")))
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void theDefaultLimitIsTwenty() throws Exception {
        for (int i = 0; i < 21; i++) {
            seedPayment("acme", PaymentStatus.AUTHORIZED, T0.plusSeconds(i));
        }

        mockMvc.perform(get("/payments").with(Tokens.reader("acme")))
                .andExpect(jsonPath("$.length()").value(20));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "101", "-1"})
    void anOutOfRangeLimitIsRefused(String limit) throws Exception {
        mockMvc.perform(get("/payments").param("limit", limit).with(Tokens.reader("acme")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("limit must be between 1 and 100"));
    }

    @Test
    void aNonNumericLimitIsRefused() throws Exception {
        mockMvc.perform(get("/payments").param("limit", "all").with(Tokens.reader("acme")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("malformed limit"));
    }
}
