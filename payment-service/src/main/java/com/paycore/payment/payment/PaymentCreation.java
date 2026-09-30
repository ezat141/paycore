package com.paycore.payment.payment;

import com.paycore.payment.acquirer.AcquirerClient;
import com.paycore.payment.acquirer.AuthorizeOutcome;
import com.paycore.payment.error.ApiException;
import com.paycore.payment.idempotency.Fingerprint;
import com.paycore.payment.idempotency.IdempotencyCache;
import com.paycore.payment.idempotency.IdempotencyKeyStore;
import com.paycore.payment.idempotency.IdempotencyProperties;
import com.paycore.payment.idempotency.KeyRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;
import java.util.UUID;

/**
 * Idempotent payment creation (spec §7) around the authorize flow (spec §5.3). Deliberately
 * not transactional: see PaymentWrites.
 *
 * <p>Postgres is the guarantee. The key row and the PENDING payment are inserted in one
 * transaction, so its primary key alone prevents two payments for one key. Redis sits in
 * front as a fast path only — see IdempotencyCache for why failing open is safe.
 */
@Service
public class PaymentCreation {

    private static final Logger log = LoggerFactory.getLogger(PaymentCreation.class);

    static final String UNKNOWN_OUTCOME = "acquirer outcome unknown; retry with the same Idempotency-Key";
    static final String IN_PROGRESS = "a request with this Idempotency-Key is in progress";

    private final PaymentWrites writes;
    private final PaymentRepository payments;
    private final IdempotencyKeyStore keys;
    private final IdempotencyCache cache;
    private final AcquirerClient acquirer;
    private final IdempotencyProperties properties;
    private final ObjectMapper json;

    public PaymentCreation(PaymentWrites writes, PaymentRepository payments, IdempotencyKeyStore keys,
                           IdempotencyCache cache, AcquirerClient acquirer, IdempotencyProperties properties,
                           ObjectMapper json) {
        this.writes = writes;
        this.payments = payments;
        this.keys = keys;
        this.cache = cache;
        this.acquirer = acquirer;
        this.properties = properties;
        this.json = json;
    }

    /** Spec §7.4, top to bottom. */
    public CreateResult create(String tenant, String idempotencyKey, PaymentRequest request) {
        String fingerprint = Fingerprint.of(request);

        Optional<CreateResult> cached = fromCache(tenant, idempotencyKey, fingerprint);
        if (cached.isPresent()) {
            return cached.get();
        }

        String owner = UUID.randomUUID().toString();
        if (cache.tryLock(tenant, idempotencyKey, fingerprint, owner) == IdempotencyCache.LockResult.HELD) {
            // Another request holds the key right now: answer from what it left, or ask for a retry.
            return fromCache(tenant, idempotencyKey, fingerprint)
                    .orElseThrow(() -> ApiException.inProgress(IN_PROGRESS));
        }
        CreateResult result;
        try {
            result = throughDatabase(tenant, idempotencyKey, fingerprint, request);
        } catch (RuntimeException e) {
            // Nothing this request may cache: a 422 or 409 is about another request's attempt, a 502
            // is never stored (spec §7.5), and a fault recorded nothing. Give up our lock so a retry
            // goes straight back to Postgres rather than waiting out lock-ttl.
            cache.release(tenant, idempotencyKey, owner);
            throw e;
        }
        // Outside the try, and IdempotencyCache never throws: once Postgres has recorded the
        // outcome, no cache fault can turn it into a 500.
        cache.complete(tenant, idempotencyKey, fingerprint, result.status(), result.body());
        return result;
    }

    /**
     * A completed entry replays, or is 422 if its fingerprint differs. An in-progress entry is 409
     * whatever its fingerprint: an in-flight attempt was never checked against Postgres, so it is
     * not authoritative, and a 422 on its word could refuse a key that Postgres would replay.
     *
     * <p>A completed entry whose body will not replay is dropped and treated as a miss, so the
     * request falls through to Postgres, which replays its own snapshot and repopulates Redis.
     */
    private Optional<CreateResult> fromCache(String tenant, String idempotencyKey, String fingerprint) {
        Optional<IdempotencyCache.Cached> cached = cache.get(tenant, idempotencyKey);
        if (cached.isEmpty()) {
            return Optional.empty();
        }
        IdempotencyCache.Entry entry = cached.get().entry();
        if (!entry.completed()) {
            throw ApiException.inProgress(IN_PROGRESS);
        }
        if (!entry.fingerprint().equals(fingerprint)) {
            throw ApiException.keyReused();
        }
        try {
            return Optional.of(replay(entry.responseStatus(), entry.responseBody()));
        } catch (RuntimeException e) {
            log.warn("cached response for an Idempotency-Key will not replay; dropping it: {}", e.getClass().getName());
            cache.discard(tenant, idempotencyKey, cached.get());
            return Optional.empty();
        }
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
        AuthorizeOutcome outcome;
        try {
            outcome = acquirer.authorize(payment.getId(), payment.getAmountMinor(),
                    payment.getCurrency(), payment.getCardToken());
        } catch (HttpClientErrorException refused) {
            // The acquirer answered, and said no: our record and theirs disagree. The payment's
            // status is left as it was (PENDING, or UNKNOWN on a re-drive) and is never moved to
            // UNKNOWN for this, because a retry will not resolve it; reconciliation (M5) must.
            // The lease is released so a retry hears this same 502, not a false "in progress".
            log.error("acquirer refused authorization of payment {}: {}", payment.getId(), refused.getStatusCode());
            releaseQuietly(tenant, idempotencyKey);
            throw ApiException.acquirerRefused("authorization", payment.getId());
        }
        Payment settled;
        try {
            settled = writes.recordAuthorizeOutcome(tenant, idempotencyKey, payment.getId(), outcome);
        } catch (RuntimeException e) {
            // The acquirer may have approved, and nothing recorded it: to the client this is no
            // answer, and it must learn its payment id. The payment keeps its status and the key
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
     * Lets the client's retry re-drive at once, as it can after no answer, rather than hear a
     * false "in progress". If the database is what failed this may fail too; then the lease
     * simply lapses after lock-ttl.
     */
    private void releaseQuietly(String tenant, String idempotencyKey) {
        try {
            keys.release(tenant, idempotencyKey);
        } catch (RuntimeException e) {
            log.warn("could not release Idempotency-Key lease; it lapses after {}", properties.lockTtl(), e);
        }
    }

    private CreateResult replay(int status, String body) {
        // Lenient on unknown fields: snapshots are never purged, and one stored before a field was
        // removed from PaymentResponse must still replay rather than become a 500.
        PaymentResponse snapshot = json.readerFor(PaymentResponse.class)
                .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .readValue(body);
        return CreateResult.replay(status, snapshot);
    }
}
