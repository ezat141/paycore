package com.paycore.payment.payment;

public record CreateResult(int status, PaymentResponse body, boolean replayed) {

    static CreateResult fresh(PaymentResponse body) {
        return new CreateResult(201, body, false);
    }

    static CreateResult replay(int status, PaymentResponse body) {
        return new CreateResult(status, body, true);
    }
}
