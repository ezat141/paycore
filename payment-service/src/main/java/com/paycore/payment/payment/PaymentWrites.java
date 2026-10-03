package com.paycore.payment.payment;

import com.paycore.payment.acquirer.AuthorizeOutcome;
import com.paycore.payment.error.ApiException;
import com.paycore.payment.idempotency.IdempotencyKeyStore;
import com.paycore.payment.idempotency.KeyRecord;
import com.paycore.payment.outbox.PaymentEvents;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

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

    public sealed interface Start {

        record Created(Payment payment) implements Start {
        }

        record Existing(KeyRecord key) implements Start {
        }
    }

    private final PaymentRepository payments;
    private final IdempotencyKeyStore keys;
    private final ObjectMapper json;
    private final PaymentEvents events;

    public PaymentWrites(PaymentRepository payments, IdempotencyKeyStore keys, ObjectMapper json, PaymentEvents events) {
        this.payments = payments;
        this.keys = keys;
        this.json = json;
        this.events = events;
    }

    /**
     * Tx 1 (spec §5.3, §7.3): the key and the PENDING payment commit together or not at all.
     * The key goes first — if it already exists, nothing is written.
     */
    @Transactional
    public Start start(String tenant, String idempotencyKey, String fingerprint, PaymentRequest request, Duration lease) {
        UUID id = UUID.randomUUID();
        if (!keys.insertIfAbsent(tenant, idempotencyKey, fingerprint, id, lease)) {
            return new Start.Existing(keys.find(tenant, idempotencyKey).orElseThrow());
        }
        Payment payment = payments.save(Payment.pending(id, tenant, request.amount(),
                request.currency(), request.cardToken(), request.description()));
        events.statusChanged(payment, null);
        return new Start.Created(payment);
    }

    /**
     * Tx 2: the outcome, and what the key should say about it. A definite outcome completes the
     * key with the response to replay. No answer releases the lease instead — a 502 is never
     * stored, because replaying it would freeze the payment in UNKNOWN for good.
     */
    @Transactional
    public Payment recordAuthorizeOutcome(String tenant, String idempotencyKey, UUID paymentId, AuthorizeOutcome outcome) {
        Payment payment = payments.findById(paymentId).orElseThrow();
        PaymentStatus before = payment.getStatus();
        switch (outcome) {
            case AuthorizeOutcome.Approved approved -> payment.authorize(approved.authorizationId());
            case AuthorizeOutcome.Declined declined -> payment.decline(declined.authorizationId(), declined.declineCode());
            case AuthorizeOutcome.NoAnswer noAnswer -> payment.markUnknown();
        }
        events.statusChanged(payment, before);
        if (payment.getStatus() == PaymentStatus.UNKNOWN) {
            keys.release(tenant, idempotencyKey);
        } else {
            keys.complete(tenant, idempotencyKey, HttpStatus.CREATED.value(),
                    json.writeValueAsString(PaymentResponse.from(payment)));
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
     * Claims a capture or void (spec §5.3 table). Two racing requests that both read version N:
     * the second to commit fails on {@code @Version}. One that reads after the other committed
     * sees the in-flight status instead.
     */
    @Transactional
    public Claim claimTransition(String tenant, UUID id, TransitionAction action, Duration staleAfter) {
        Payment payment = payments.findByIdAndTenant(id, tenant).orElseThrow(ApiException::notFound);
        PaymentStatus status = payment.getStatus();
        if (status == action.done) {
            return new Claim.AlreadyDone(payment);
        }
        if (status == action.inFlight) {
            // The JVM's clock, unlike the key store's lease checks, which use Postgres's now().
            // Skew between the two only moves when a re-drive is allowed: @Version still lets one
            // claim win, and the acquirer's capture and void are idempotent on the authorization id.
            if (payment.getTransitionStartedAt().isAfter(Instant.now().minus(staleAfter))) {
                throw ApiException.inProgress(action.verb + " in progress");
            }
            payment.refreshTransitionClaim();
            return new Claim.Claimed(payment);
        }
        if (status == PaymentStatus.AUTHORIZED) {
            payment.beginTransition(action.inFlight);
            events.statusChanged(payment, PaymentStatus.AUTHORIZED);
            return new Claim.Claimed(payment);
        }
        throw ApiException.conflict("cannot " + action.verb + " a payment that is " + status);
    }

    @Transactional
    public Payment completeTransition(UUID id, TransitionAction action) {
        Payment payment = payments.findById(id).orElseThrow();
        // Sequential completions find it done; truly concurrent ones lose on @Version and get a
        // 409 whose retry returns 200.
        if (payment.getStatus() != action.done) {
            PaymentStatus before = payment.getStatus();
            payment.completeTransition(action.done);
            events.statusChanged(payment, before);
        }
        return payment;
    }
}
