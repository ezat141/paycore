package com.paycore.history.config;

import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Spec §3.1–3.2, in order: the client's scope <em>and</em> the user's permission, then a tenant.
 *
 * <p>Both authorities are required because they answer different questions. A user holding
 * {@code payments:write} who signs in through a client granted only {@code payments:read}
 * must not write: the scope is the client's ceiling, the permission is the user's right.
 *
 * <p>The tenant check comes second so a token lacking everything is told about permissions
 * first. That includes every client-credentials token today: AuthCore adds {@code tenant},
 * {@code roles} and {@code permissions} together or not at all, so such a token carries no
 * permissions and is refused for that. The tenant check is defence in depth, for a token that
 * carries permissions but no tenant; it is refused with its own reason, because no amount of
 * extra scope supplies a claim the token structurally lacks.
 */
public final class PaymentsAuthorization implements AuthorizationManager<RequestAuthorizationContext> {

    public static final String INSUFFICIENT_PERMISSION = "insufficient permission";
    public static final String NO_TENANT = "token carries no tenant";

    private final String permission;

    private PaymentsAuthorization(String permission) {
        this.permission = permission;
    }

    public static PaymentsAuthorization requires(String permission) {
        return new PaymentsAuthorization(permission);
    }

    @Override
    public AuthorizationResult authorize(Supplier<? extends Authentication> authentication,
                                         RequestAuthorizationContext context) {
        Authentication current = authentication.get();
        if (current == null) {
            return new DetailedDenial(INSUFFICIENT_PERMISSION);
        }
        Set<String> granted = current.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
        if (!granted.contains("SCOPE_" + permission) || !granted.contains(permission)) {
            return new DetailedDenial(INSUFFICIENT_PERMISSION);
        }
        // getClaimAsString would coerce a list or a number into a tenant; only a real string
        // claim counts, so a claim of the wrong shape is treated the same as a missing one.
        if (!(current.getPrincipal() instanceof Jwt jwt)
                || !(jwt.getClaims().get("tenant") instanceof String tenant)
                || tenant.isBlank()) {
            return new DetailedDenial(NO_TENANT);
        }
        return new AuthorizationDecision(true);
    }
}
