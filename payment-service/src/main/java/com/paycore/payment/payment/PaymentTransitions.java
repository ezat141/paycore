package com.paycore.payment.payment;

import com.paycore.payment.acquirer.AcquirerClient;
import com.paycore.payment.acquirer.ActionOutcome;
import com.paycore.payment.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;

import java.util.UUID;

/** Capture and void (spec §5.3). Not transactional: the acquirer call sits between two transactions. */
@Service
public class PaymentTransitions {

    private static final Logger log = LoggerFactory.getLogger(PaymentTransitions.class);

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
        ActionOutcome outcome;
        try {
            outcome = action == TransitionAction.CAPTURE
                    ? acquirer.capture(authorizationId)
                    : acquirer.voidAuthorization(authorizationId);
        } catch (HttpClientErrorException refused) {
            // The acquirer answered, and said no: it does not know the authorization, or holds it
            // in a state that forbids this move. Our record and theirs disagree, so neither
            // completing nor reverting is safe; the payment stays in flight until reconciliation
            // (M5) settles it against the acquirer's own record.
            log.error("acquirer refused {} of payment {}: {}", action.verb, claimed.getId(), refused.getStatusCode());
            throw ApiException.acquirerRefused(action.verb);
        }
        if (outcome instanceof ActionOutcome.NoAnswer) {
            // Left in CAPTURING/VOIDING, never reverted: the acquirer may have done it. Once the
            // claim goes stale a retry re-drives it, which is safe because the acquirer's capture
            // and void are idempotent on the authorization id.
            throw ApiException.badGateway("acquirer outcome unknown; retry the " + action.verb, null);
        }
        try {
            return PaymentResponse.from(writes.completeTransition(claimed.getId(), action));
        } catch (OptimisticLockingFailureException concurrentCompletion) {
            throw concurrentCompletion;   // another request completed it: 409, and its retry returns 200
        } catch (RuntimeException e) {
            // The acquirer did it, and recording that failed. To the client that is no answer, as
            // on authorize: the payment stays CAPTURING/VOIDING, and once the claim goes stale a
            // retry re-drives it, which the acquirer's idempotent capture and void make safe.
            log.error("could not record the acquirer's {} of payment {}", action.verb, claimed.getId(), e);
            throw ApiException.badGateway("acquirer outcome unknown; retry the " + action.verb, null);
        }
    }
}
