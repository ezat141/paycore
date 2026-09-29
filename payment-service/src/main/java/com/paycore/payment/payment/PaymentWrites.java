package com.paycore.payment.payment;

import com.paycore.payment.acquirer.AuthorizeOutcome;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Every database transaction the payment flows need, and nothing else. The flows that call
 * the acquirer (PaymentCreation, PaymentTransitions) are not transactional and call in here
 * through the Spring proxy — so no transaction can be open across a network call (spec §5.3).
 */
@Service
public class PaymentWrites {

    private final PaymentRepository payments;

    public PaymentWrites(PaymentRepository payments) {
        this.payments = payments;
    }

    /** Tx 1: the PENDING row is committed before the acquirer is called — evidence if we crash. */
    @Transactional
    public Payment startPending(String tenant, PaymentRequest request) {
        return payments.save(Payment.pending(UUID.randomUUID(), tenant, request.amount(),
                request.currency(), request.cardToken(), request.description()));
    }

    /** Tx 2. */
    @Transactional
    public Payment recordAuthorizeOutcome(UUID paymentId, AuthorizeOutcome outcome) {
        Payment payment = payments.findById(paymentId).orElseThrow();
        switch (outcome) {
            case AuthorizeOutcome.Approved approved -> payment.authorize(approved.authorizationId());
            case AuthorizeOutcome.Declined declined -> payment.decline(declined.authorizationId(), declined.declineCode());
            case AuthorizeOutcome.NoAnswer noAnswer -> payment.markUnknown();
        }
        return payment;
    }
}
