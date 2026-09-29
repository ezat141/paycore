package com.paycore.payment.acquirer;

/**
 * What the acquirer said about an authorization. {@link NoAnswer} is a first-class outcome, not
 * an exception: a timeout does not tell us whether the card was charged, and making it a case
 * of a sealed type means no caller can forget that.
 */
public sealed interface AuthorizeOutcome {

    record Approved(String authorizationId) implements AuthorizeOutcome {
    }

    record Declined(String authorizationId, String declineCode) implements AuthorizeOutcome {
    }

    record NoAnswer() implements AuthorizeOutcome {
    }
}
