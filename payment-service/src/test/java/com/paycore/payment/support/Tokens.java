package com.paycore.payment.support;

import com.paycore.payment.config.AuthCoreAuthoritiesConverter;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;

import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

/**
 * Tokens shaped like AuthCore's, with authorities derived by the service's real converter —
 * so a test exercises the same claim-to-authority mapping production does, not a shortcut.
 */
public final class Tokens {

    private static final List<String> BOTH = List.of("payments:read", "payments:write");

    private Tokens() {
    }

    public static JwtRequestPostProcessor writer(String tenant) {
        return token(tenant, BOTH, BOTH);
    }

    public static JwtRequestPostProcessor reader(String tenant) {
        return token(tenant, List.of("payments:read"), List.of("payments:read"));
    }

    /** A client-credentials-shaped token: scopes, but no user and so no tenant or permissions. */
    public static JwtRequestPostProcessor withoutTenant() {
        return token(null, BOTH, BOTH);
    }

    public static JwtRequestPostProcessor token(String tenant, List<String> scopes, List<String> permissions) {
        return jwt()
                .jwt(builder -> {
                    builder.subject(tenant == null ? "authcore-machine" : "user@" + tenant)
                            .claim("scope", String.join(" ", scopes))
                            .claim("permissions", permissions);
                    if (tenant != null) {
                        builder.claim("tenant", tenant);
                    }
                })
                .authorities(new AuthCoreAuthoritiesConverter());
    }
}
