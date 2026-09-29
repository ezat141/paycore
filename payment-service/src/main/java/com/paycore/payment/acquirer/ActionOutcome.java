package com.paycore.payment.acquirer;

/** What the acquirer said about a capture or void. */
public sealed interface ActionOutcome {

    record Completed() implements ActionOutcome {
    }

    record NoAnswer() implements ActionOutcome {
    }
}
