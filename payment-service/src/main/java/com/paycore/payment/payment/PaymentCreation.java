package com.paycore.payment.payment;

import com.paycore.payment.acquirer.AcquirerClient;
import com.paycore.payment.acquirer.AuthorizeOutcome;
import com.paycore.payment.error.ApiException;
import com.paycore.payment.idempotency.Fingerprint;
import com.paycore.payment.idempotency.IdempotencyKeyStore;
import com.paycore.payment.idempotency.IdempotencyProperties;
import com.paycore.payment.idempotency.KeyRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Idempotent payment creation (spec §7) around the authorize flow (spec §5.3). Deliberately
 * not transactional: see PaymentWrites.
 *
 * <p>Postgres is the guarantee. The key row and the PENDING payment are inserted in one
 * transaction, so its primary key alone prevents two payments for one key.
 */
@Service
public class PaymentCreation {

    private static final Logger log = LoggerFactory.getLogger(PaymentCreation.class);

    static final String UNKNOWN_OUTCOME = "acquirer outcome unknown; retry with the same Idempotency-Key";
    static final String IN_PROGRESS = "a request with this Idempotency-Key is in progress";

    private final PaymentWrites writes;
    private final PaymentRepository payments;
    private final IdempotencyKeyStore keys;
    private final AcquirerClient acquirer;
    private final IdempotencyProperties properties;
    private final ObjectMapper json;

    public PaymentCreation(PaymentWrites writes, PaymentRepository payments, IdempotencyKeyStore keys,
                           AcquirerClient acquirer, IdempotencyProperties properties, ObjectMapper json) {
        this.writes = writes;
        this.payments = payments;
        this.keys = keys;
        this.acquirer = acquirer;
        this.properties = properties;
        this.json = json;
    }

    public CreateResult create(String tenant, String idempotencyKey, PaymentRequest request) {
        return throughDatabase(tenant, idempotencyKey, Fingerprint.of(request), request);
    }

    private CreateResult throughDatabase(String tenant, String idempotencyKey, String fingerprint, PaymentRequest request) {
        return switch (writes.start(tenant, idempotencyKey, fingerprint, request, properties.lockTtl())) {
            case PaymentWrites.Start.Created created -> authorize(tenant, idempotencyKey, created.payment());
            case PaymentWrites.Start.Existing existing -> resume(tenant, idempotencyKey, fingerprint, existing.key());
        };
    }

    /** The key already exists (spec §7.4, "PK violation"). */
    private CreateResult resume(String tenant, String idempotencyKey, String fingerprint, KeyRecord key) {
        if (!key.fingerprint().equals(fingerprint)) {
            throw ApiException.keyReused();
        }
        if (key.completed()) {
            return replay(key.responseStatus(), key.responseBody());
        }
        if (key.leaseLive() || !keys.claimExpired(tenant, idempotencyKey, properties.lockTtl())) {
            throw ApiException.inProgress(IN_PROGRESS);
        }
        // The lease lapsed: the earlier attempt got no answer, or its process died with the
        // payment PENDING. Either way, re-drive the same payment (spec §7.5).
        return authorize(tenant, idempotencyKey, payments.findById(key.paymentId()).orElseThrow());
    }

    /**
     * The payment id is the acquirer's reference. On a re-drive, a charge that did go through
     * comes back as that same authorization rather than a second one.
     */
    private CreateResult authorize(String tenant, String idempotencyKey, Payment payment) {
        AuthorizeOutcome outcome = acquirer.authorize(payment.getId(), payment.getAmountMinor(),
                payment.getCurrency(), payment.getCardToken());
        Payment settled;
        try {
            settled = writes.recordAuthorizeOutcome(tenant, idempotencyKey, payment.getId(), outcome);
        } catch (RuntimeException e) {
            // The acquirer may have approved, and nothing recorded it: to the client this is no
            // answer, and it must learn its payment id. The payment stays PENDING and the key
            // IN_PROGRESS, so a retry with the same key re-drives it under the same reference.
            log.error("could not record the acquirer's answer for payment {}", payment.getId(), e);
            releaseQuietly(tenant, idempotencyKey);
            throw ApiException.badGateway(UNKNOWN_OUTCOME, payment.getId());
        }
        if (settled.getStatus() == PaymentStatus.UNKNOWN) {
            throw ApiException.badGateway(UNKNOWN_OUTCOME, settled.getId());
        }
        return CreateResult.fresh(PaymentResponse.from(settled));
    }

    /**
     * Lets the client's retry re-drive at once, as it can after no answer. If the database is
     * what failed this may fail too; then the lease simply lapses after lock-ttl.
     */
    private void releaseQuietly(String tenant, String idempotencyKey) {
        try {
            keys.release(tenant, idempotencyKey);
        } catch (RuntimeException e) {
            log.warn("could not release Idempotency-Key lease; it lapses after {}", properties.lockTtl(), e);
        }
    }

    private CreateResult replay(int status, String body) {
        return CreateResult.replay(status, json.readValue(body, PaymentResponse.class));
    }
}
