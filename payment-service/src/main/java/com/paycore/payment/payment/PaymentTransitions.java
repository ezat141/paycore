package com.paycore.payment.payment;

import com.paycore.payment.acquirer.AcquirerClient;
import com.paycore.payment.acquirer.ActionOutcome;
import com.paycore.payment.error.ApiException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.util.UUID;

/** Capture and void (spec §5.3). Not transactional: the acquirer call sits between two transactions. */
@Service
public class PaymentTransitions {

    private final PaymentWrites writes;
    private final AcquirerClient acquirer;
    private final PaymentProperties properties;

    public PaymentTransitions(PaymentWrites writes, AcquirerClient acquirer, PaymentProperties properties) {
        this.writes = writes;
        this.acquirer = acquirer;
        this.properties = properties;
    }

    public PaymentResponse capture(String tenant, UUID id) {
        return run(tenant, id, TransitionAction.CAPTURE);
    }

    public PaymentResponse voidPayment(String tenant, UUID id) {
        return run(tenant, id, TransitionAction.VOID);
    }

    private PaymentResponse run(String tenant, UUID id, TransitionAction action) {
        PaymentWrites.Claim claim;
        try {
            claim = writes.claimTransition(tenant, id, action, properties.transitionStaleAfter());
        } catch (OptimisticLockingFailureException lostRace) {
            throw ApiException.conflict("payment was modified concurrently; retry");
        }
        return switch (claim) {
            case PaymentWrites.Claim.AlreadyDone done -> PaymentResponse.from(done.payment());
            case PaymentWrites.Claim.Claimed claimed -> callAcquirer(claimed.payment(), action);
        };
    }

    private PaymentResponse callAcquirer(Payment claimed, TransitionAction action) {
        String authorizationId = claimed.getAcquirerAuthorizationId();
        ActionOutcome outcome = action == TransitionAction.CAPTURE
                ? acquirer.capture(authorizationId)
                : acquirer.voidAuthorization(authorizationId);
        if (outcome instanceof ActionOutcome.NoAnswer) {
            // Left in CAPTURING/VOIDING, never reverted: the acquirer may have done it. Once the
            // claim goes stale a retry re-drives it, which is safe because the acquirer's capture
            // and void are idempotent on the authorization id.
            throw ApiException.badGateway("acquirer outcome unknown; retry the " + action.verb, null);
        }
        return PaymentResponse.from(writes.completeTransition(claimed.getId(), action));
    }
}
