package com.paycore.payment.payment;

import com.paycore.payment.acquirer.AuthorizeOutcome;
import com.paycore.payment.error.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
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

    public sealed interface Claim {

        record Claimed(Payment payment) implements Claim {
        }

        record AlreadyDone(Payment payment) implements Claim {
        }
    }

    /**
     * Claims a capture or void (spec §5.3 table). Two racing requests both read version N; the
     * second to commit fails on {@code @Version} and never calls the acquirer.
     */
    @Transactional
    public Claim claimTransition(String tenant, UUID id, TransitionAction action, Duration staleAfter) {
        Payment payment = payments.findByIdAndTenant(id, tenant).orElseThrow(ApiException::notFound);
        PaymentStatus status = payment.getStatus();
        if (status == action.done) {
            return new Claim.AlreadyDone(payment);
        }
        if (status == action.inFlight) {
            if (payment.getTransitionStartedAt().isAfter(Instant.now().minus(staleAfter))) {
                throw ApiException.inProgress(action.verb + " in progress");
            }
            payment.refreshTransitionClaim();
            return new Claim.Claimed(payment);
        }
        if (status == PaymentStatus.AUTHORIZED) {
            payment.beginTransition(action.inFlight);
            return new Claim.Claimed(payment);
        }
        throw ApiException.conflict("cannot " + action.verb + " a payment that is " + status);
    }

    @Transactional
    public Payment completeTransition(UUID id, TransitionAction action) {
        Payment payment = payments.findById(id).orElseThrow();
        // A stale re-drive and the slow original can both arrive here; the second finds it done.
        if (payment.getStatus() != action.done) {
            payment.completeTransition(action.done);
        }
        return payment;
    }
}
