package com.paycore.payment.payment;

enum TransitionAction {

    CAPTURE("capture", PaymentStatus.CAPTURING, PaymentStatus.CAPTURED),
    VOID("void", PaymentStatus.VOIDING, PaymentStatus.VOIDED);

    final String verb;
    final PaymentStatus inFlight;
    final PaymentStatus done;

    TransitionAction(String verb, PaymentStatus inFlight, PaymentStatus done) {
        this.verb = verb;
        this.inFlight = inFlight;
        this.done = done;
    }
}
