package com.paycore.payment.config;

import com.paycore.payment.support.PaymentServiceTest;
import com.paycore.payment.support.Tokens;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Refusals only. Every assertion here is decided by the filter chain before any controller
 * exists, so these tests stay true whatever the endpoints later do.
 */
class SecurityBoundaryTest extends PaymentServiceTest {

    private static final String INVALID_BODY = "{ this is not json";

    @Test
    void noTokenIsA401InThePlatformShape() throws Exception {
        mockMvc.perform(get("/payments"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(jsonPath("$.error").value("unauthorized"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.path").value("/payments"));
    }

    @Test
    void aReadOnlyTokenCannotCreateAPayment() throws Exception {
        mockMvc.perform(post("/payments").with(Tokens.reader("acme")))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE))
                .andExpect(jsonPath("$.error").value("forbidden"))
                .andExpect(jsonPath("$.detail").value("insufficient permission"));
    }

    /** The client was granted the scope, but the user does not hold the permission. */
    @Test
    void scopeWithoutPermissionIsRefused() throws Exception {
        mockMvc.perform(post("/payments")
                        .with(Tokens.token("acme", List.of("payments:write"), List.of())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("insufficient permission"));
    }

    /** The user holds the permission, but signed in through a client never granted the scope. */
    @Test
    void permissionWithoutScopeIsRefused() throws Exception {
        mockMvc.perform(post("/payments")
                        .with(Tokens.token("acme", List.of("payments:read"), List.of("payments:write"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("insufficient permission"));
    }

    @Test
    void aTokenWithNoTenantIsRefusedWithItsOwnDetail() throws Exception {
        mockMvc.perform(get("/payments").with(Tokens.withoutTenant()))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE))
                .andExpect(jsonPath("$.detail").value("token carries no tenant"));
    }

    @Test
    void authorizationRunsBeforeTheBodyIsParsed() throws Exception {
        mockMvc.perform(post("/payments").with(Tokens.reader("acme"))
                        .contentType(MediaType.APPLICATION_JSON).content(INVALID_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    void theTenantCheckAlsoRunsBeforeTheBodyIsParsed() throws Exception {
        mockMvc.perform(post("/payments").with(Tokens.withoutTenant())
                        .contentType(MediaType.APPLICATION_JSON).content(INVALID_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("token carries no tenant"));
    }

    @Test
    void aPathWithNoRuleIsRefusedEvenWithAFullToken() throws Exception {
        mockMvc.perform(get("/admin").with(Tokens.writer("acme")))
                .andExpect(status().isForbidden());
    }
}
