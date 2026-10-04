package com.paycore.history.history;

import com.paycore.history.support.HistoryServiceTest;
import com.paycore.history.support.Tokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Spec §6.5: the read API, its ordering, tenant isolation and trust boundary. */
class HistoryApiTest extends HistoryServiceTest {

    private static final Instant AT = Instant.parse("2026-10-03T10:15:30.123456Z");

    @Autowired
    HistoryStore store;

    @Test
    void theOwnTenantReadsThePaymentsHistoryInVersionOrder() throws Exception {
        UUID payment = seed("acme", 2, 0, 1);

        mockMvc.perform(get("/payments/{id}/history", payment).with(Tokens.reader("acme")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].version").value(0))
                .andExpect(jsonPath("$[0].fromStatus").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$[0].toStatus").value("PENDING"))
                .andExpect(jsonPath("$[2].version").value(2))
                .andExpect(jsonPath("$[0].tenant").doesNotExist());
    }

    /** I12: 404, not 403: a refusal would confirm the payment exists. */
    @Test
    void anotherTenantsPaymentIsNotFound() throws Exception {
        UUID payment = seed("acme", 0);

        mockMvc.perform(get("/payments/{id}/history", payment).with(Tokens.reader("globex")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("no history for this payment"));
    }

    @Test
    void aPaymentWithNoHistoryIsNotFound() throws Exception {
        mockMvc.perform(get("/payments/{id}/history", UUID.randomUUID()).with(Tokens.reader("acme")))
                .andExpect(status().isNotFound());
    }

    @Test
    void aMalformedIdIsABadRequest() throws Exception {
        mockMvc.perform(get("/payments/not-a-uuid/history").with(Tokens.reader("acme")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("malformed id"));
    }

    @Test
    void noTokenIsA401InThePlatformShape() throws Exception {
        mockMvc.perform(get("/payments/{id}/history", UUID.randomUUID()))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(jsonPath("$.error").value("unauthorized"));
    }

    @Test
    void scopeWithoutPermissionIsRefused() throws Exception {
        mockMvc.perform(get("/payments/{id}/history", UUID.randomUUID())
                        .with(Tokens.token("acme", List.of("payments:read"), List.of())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("insufficient permission"));
    }

    @Test
    void aTokenWithNoTenantIsRefused() throws Exception {
        mockMvc.perform(get("/payments/{id}/history", UUID.randomUUID()).with(Tokens.withoutTenant()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("token carries no tenant"));
    }

    @Test
    void anUnmappedRouteIsNoSuchOperation() throws Exception {
        mockMvc.perform(get("/payments").with(Tokens.reader("acme")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("no such operation"));
    }

    private UUID seed(String tenant, long... versions) {
        UUID payment = UUID.randomUUID();
        String[] statuses = {"PENDING", "AUTHORIZED", "CAPTURING", "CAPTURED"};
        for (long v : versions) {
            store.insertIfAbsent(new PaymentStatusChanged(UUID.randomUUID(), payment, tenant, v,
                    v == 0 ? null : statuses[(int) v - 1], statuses[(int) v], 1999L, "USD", null, AT));
        }
        return payment;
    }
}
