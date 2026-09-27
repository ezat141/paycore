package com.paycore.acquirer;

public record ActionResponse(String authorizationId, AuthorizationStatus status) {

    static ActionResponse from(Authorization authorization) {
        return new ActionResponse(authorization.authorizationId(), authorization.status());
    }
}
