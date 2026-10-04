package com.paycore.history.config;

import com.paycore.history.support.HistoryServiceTest;
import com.paycore.history.support.Tokens;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The copied authorization rules, pinned in history-service itself: a copy that drifts from
 * payment-service's behaviour must fail here, not rely on payment-service's tests. Each refusal
 * is on the history path, where a wrongly admitted token would be answered by the controller
 * (a 404), so a refusal proves the chain stopped it first.
 */
class SecurityBoundaryTest extends HistoryServiceTest {

    /** The user holds the permission, but signed in through a client never granted the scope. */
    @Test
    void permissionWithoutScopeIsRefused() throws Exception {
        mockMvc.perform(get("/payments/{id}/history", UUID.randomUUID())
                        .with(Tokens.token("acme", List.of(), List.of("payments:read"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("insufficient permission"));
    }

    /**
     * The converter must not let a {@code permissions} entry masquerade as a {@code SCOPE_}
     * authority: the client's ceiling comes only from the {@code scope} claim.
     */
    @Test
    void aPermissionNamedLikeAScopeCannotSupplyTheScope() throws Exception {
        mockMvc.perform(get("/payments/{id}/history", UUID.randomUUID())
                        .with(Tokens.token("acme", List.of(), List.of("payments:read", "SCOPE_payments:read"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("insufficient permission"));
    }

    /** A tenant claim present but blank is not a tenant: a string, but not a real one. */
    @Test
    void aBlankTenantClaimIsRefusedAsNoTenant() throws Exception {
        mockMvc.perform(get("/payments/{id}/history", UUID.randomUUID())
                        .with(Tokens.token(" ", List.of("payments:read"), List.of("payments:read"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("token carries no tenant"));
    }

    /** A list-typed {@code tenant} claim is not a tenant: only a non-blank string counts. */
    @Test
    void aListTypedTenantClaimIsRefusedAsNoTenant() throws Exception {
        mockMvc.perform(get("/payments/{id}/history", UUID.randomUUID())
                        .with(jwt().jwt(builder -> builder
                                        .subject("user@acme")
                                        .claim("scope", "payments:read")
                                        .claim("permissions", List.of("payments:read"))
                                        .claim("tenant", List.of("acme", "globex")))
                                .authorities(new AuthCoreAuthoritiesConverter())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("token carries no tenant"));
    }

    /** The one rule is a GET: any other method on the same path is an operation that does not exist. */
    @Test
    void aNonGetOnTheHistoryPathIsNoSuchOperation() throws Exception {
        mockMvc.perform(post("/payments/{id}/history", UUID.randomUUID()).with(Tokens.writer("acme")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("no such operation"));
    }

    @Test
    void onlyHealthIsPublicNotInfo() throws Exception {
        mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isUnauthorized());
    }
}
