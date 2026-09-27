package com.paycore.acquirer;

import java.util.Objects;

record Authorization(
        String authorizationId,
        String reference,
        long amount,
        String currency,
        String cardToken,
        AuthorizationStatus status,
        String declineCode) {

    boolean sameRequestAs(AuthorizationRequest request) {
        return amount == request.amount()
                && Objects.equals(currency, request.currency())
                && Objects.equals(cardToken, request.cardToken());
    }

    Authorization withStatus(AuthorizationStatus next) {
        return new Authorization(authorizationId, reference, amount, currency, cardToken, next, declineCode);
    }
}
