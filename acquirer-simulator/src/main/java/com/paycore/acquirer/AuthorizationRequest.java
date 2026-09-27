package com.paycore.acquirer;

/** {@code reference} is the merchant's own id for the charge — PayCore sends its payment id. */
public record AuthorizationRequest(String reference, long amount, String currency, String cardToken) {
}
