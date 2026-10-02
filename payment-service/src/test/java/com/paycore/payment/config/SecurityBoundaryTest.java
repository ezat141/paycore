package com.paycore.payment.config;

import com.paycore.payment.support.PaymentServiceTest;
import com.paycore.payment.support.Tokens;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Refusals, and the matching pass-the-chain cases. A suite that only proves refusals would
 * stay green if every rule were replaced by an unconditional denial, so each rule here also
 * has a test proving the token it is meant to admit gets through. No controller exists yet,
 * so a passing case is only ever asserted as "not 401, not 403" — that stays true once later
 * tasks add controllers and the status becomes 200/201/404/etc.
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

    /** Scope and permission are checked before tenant: neither present still says "permission". */
    @Test
    void aTokenWithNeitherPermissionNorTenantIsRefusedForPermissionFirst() throws Exception {
        mockMvc.perform(post("/payments").with(Tokens.token(null, List.of(), List.of())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("insufficient permission"));
    }

    /**
     * A real client-credentials token, shaped as AuthCore issues it: the scopes, and no tenant,
     * roles or permissions claim. It lacks the permission, which is checked first, so it never
     * reaches the tenant check.
     */
    @Test
    void aClientCredentialsTokenIsRefusedForLackingPermission() throws Exception {
        mockMvc.perform(get("/payments").with(Tokens.clientCredentials("payments:read", "payments:write")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("insufficient permission"));
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
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("no such operation"));
    }

    @Test
    void onlyHealthIsPublicNotInfo() throws Exception {
        mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isUnauthorized());
    }

    // -- Pass-the-chain: proves the rule admits the token it is meant to admit, not just that
    // -- it refuses everything else. Each of these kills a mutant that a refusal-only suite lets
    // -- through: an always-deny final decision, a GET rule requiring the write permission, a
    // -- POST matcher missing capture/void, or a tenant check reordered ahead of the scope check.

    @Test
    void aReaderPassesTheChainOnListPayments() throws Exception {
        mockMvc.perform(get("/payments").with(Tokens.reader("acme")))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotIn(401, 403));
    }

    @Test
    void aReaderPassesTheChainOnGetOnePayment() throws Exception {
        mockMvc.perform(get("/payments/" + UUID.randomUUID()).with(Tokens.reader("acme")))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotIn(401, 403));
    }

    @Test
    void aWriterPassesTheChainOnCreatePayment() throws Exception {
        mockMvc.perform(post("/payments").with(Tokens.writer("acme")))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotIn(401, 403));
    }

    @Test
    void aWriterPassesTheChainOnCapture() throws Exception {
        mockMvc.perform(post("/payments/" + UUID.randomUUID() + "/capture").with(Tokens.writer("acme")))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotIn(401, 403));
    }

    @Test
    void aWriterPassesTheChainOnVoid() throws Exception {
        mockMvc.perform(post("/payments/" + UUID.randomUUID() + "/void").with(Tokens.writer("acme")))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotIn(401, 403));
    }

    // -- Hardening: a permission claim must not be able to stand in for a scope, and a tenant
    // -- claim of the wrong shape must not be able to stand in for a real tenant.

    /**
     * The converter must not let a {@code permissions} entry masquerade as a {@code SCOPE_}
     * authority: the client's ceiling comes only from the {@code scope} claim.
     */
    @Test
    void aPermissionNamedLikeAScopeCannotSupplyTheScope() throws Exception {
        mockMvc.perform(post("/payments")
                        .with(Tokens.token("acme", List.of(), List.of("payments:write", "SCOPE_payments:write"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("insufficient permission"));
    }

    /** A list-typed {@code tenant} claim is not a tenant: only a non-blank string counts. */
    @Test
    void aListTypedTenantClaimIsRefusedAsNoTenant() throws Exception {
        mockMvc.perform(get("/payments")
                        .with(jwt().jwt(builder -> builder
                                        .subject("user@acme")
                                        .claim("scope", "payments:read")
                                        .claim("permissions", List.of("payments:read"))
                                        .claim("tenant", List.of("acme", "globex")))
                                .authorities(new AuthCoreAuthoritiesConverter())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("token carries no tenant"));
    }

    /** A tenant claim present but blank is not a tenant either: a string, but not a real one. */
    @Test
    void aBlankTenantClaimIsRefusedAsNoTenant() throws Exception {
        mockMvc.perform(get("/payments")
                        .with(Tokens.token(" ", List.of("payments:read"), List.of("payments:read"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("token carries no tenant"));
    }
}
