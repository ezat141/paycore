package com.paycore.payment.payment;

import com.paycore.payment.acquirer.AcquirerClient;
import com.paycore.payment.acquirer.AuthorizeOutcome;
import com.paycore.payment.error.ApiException;
import org.springframework.stereotype.Service;

/** The authorize flow (spec §5.3). Deliberately not transactional: see PaymentWrites. */
@Service
public class PaymentCreation {

    static final String UNKNOWN_OUTCOME = "acquirer outcome unknown; retry with the same Idempotency-Key";

    private final PaymentWrites writes;
    private final AcquirerClient acquirer;

    public PaymentCreation(PaymentWrites writes, AcquirerClient acquirer) {
        this.writes = writes;
        this.acquirer = acquirer;
    }

    public PaymentResponse create(String tenant, PaymentRequest request) {
        Payment pending = writes.startPending(tenant, request);
        AuthorizeOutcome outcome = acquirer.authorize(pending.getId(), pending.getAmountMinor(),
                pending.getCurrency(), pending.getCardToken());
        Payment settled = writes.recordAuthorizeOutcome(pending.getId(), outcome);
        if (settled.getStatus() == PaymentStatus.UNKNOWN) {
            throw ApiException.badGateway(UNKNOWN_OUTCOME, settled.getId());
        }
        return PaymentResponse.from(settled);
    }
}
