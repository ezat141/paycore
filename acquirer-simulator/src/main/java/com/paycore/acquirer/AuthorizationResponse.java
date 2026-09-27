package com.paycore.acquirer;

public record AuthorizationResponse(
        String authorizationId, String reference, AuthorizationStatus status, String declineCode) {

    static AuthorizationResponse from(Authorization authorization) {
        return new AuthorizationResponse(authorization.authorizationId(), authorization.reference(),
                authorization.status(), authorization.declineCode());
    }
}
