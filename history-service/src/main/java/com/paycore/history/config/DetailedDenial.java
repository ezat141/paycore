package com.paycore.history.config;

import org.springframework.security.authorization.AuthorizationDecision;

/**
 * A denial that says why. Spring's {@code AuthorizationFilter} throws an
 * {@code AuthorizationDeniedException} carrying this result, so the 403 handler can tell a
 * missing permission from a missing tenant — the same status, not the same failure.
 */
public final class DetailedDenial extends AuthorizationDecision {

    private final String detail;

    public DetailedDenial(String detail) {
        super(false);
        this.detail = detail;
    }

    public String detail() {
        return detail;
    }
}
