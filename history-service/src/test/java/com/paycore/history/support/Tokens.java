package com.paycore.history.support;

import com.paycore.history.config.AuthCoreAuthoritiesConverter;
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

    /**
     * Scopes and permissions, but no tenant. AuthCore does not issue this today: it adds
     * {@code tenant}, {@code roles} and {@code permissions} together or not at all. It exists to
     * pin the tenant check, which is defence in depth for exactly this shape.
     */
    public static JwtRequestPostProcessor withoutTenant() {
        return token(null, BOTH, BOTH);
    }

    /**
     * What AuthCore issues for client credentials: scopes only. No user, so no tenant, roles or
     * permissions claim at all.
     */
    public static JwtRequestPostProcessor clientCredentials(String... scopes) {
        return jwt()
                .jwt(builder -> builder.subject("authcore-machine").claim("scope", String.join(" ", scopes)))
                .authorities(new AuthCoreAuthoritiesConverter());
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
