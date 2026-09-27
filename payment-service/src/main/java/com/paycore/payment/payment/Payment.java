package com.paycore.payment.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * A card payment. Every state change goes through {@link #moveTo}, so an edge not in
 * {@link PaymentStatus}'s table throws before anything is written.
 *
 * <p>{@code version} is a wrapper type on purpose: Spring Data treats a {@code null} version as
 * "new" and calls {@code persist} rather than {@code merge} for an application-assigned id.
 */
@Entity
@Table(name = "payments")
public class Payment {

    @Id
    private UUID id;

    @Column(nullable = false, length = 100)
    private String tenant;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "card_token", nullable = false, length = 64)
    private String cardToken;

    @Column(length = 255)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PaymentStatus status;

    @Column(name = "decline_code", length = 64)
    private String declineCode;

    @Column(name = "acquirer_authorization_id", length = 64)
    private String acquirerAuthorizationId;

    @Column(name = "transition_started_at")
    private Instant transitionStartedAt;

    @Version
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Payment() {
    }

    public static Payment pending(UUID id, String tenant, long amountMinor, String currency,
                                  String cardToken, String description) {
        Payment payment = new Payment();
        payment.id = id;
        payment.tenant = tenant;
        payment.amountMinor = amountMinor;
        payment.currency = currency;
        payment.cardToken = cardToken;
        payment.description = description;
        payment.status = PaymentStatus.PENDING;
        payment.createdAt = now();
        payment.updatedAt = payment.createdAt;
        return payment;
    }

    public void authorize(String authorizationId) {
        moveTo(PaymentStatus.AUTHORIZED);
        this.acquirerAuthorizationId = authorizationId;
    }

    public void decline(String authorizationId, String declineCode) {
        moveTo(PaymentStatus.DECLINED);
        this.acquirerAuthorizationId = authorizationId;
        this.declineCode = declineCode;
    }

    /** A re-drive that again gets no answer leaves the payment where it was. */
    public void markUnknown() {
        if (status != PaymentStatus.UNKNOWN) {
            moveTo(PaymentStatus.UNKNOWN);
        }
    }

    /** Claims a capture or void before the acquirer is called (spec §5.3). */
    public void beginTransition(PaymentStatus inFlight) {
        if (inFlight != PaymentStatus.CAPTURING && inFlight != PaymentStatus.VOIDING) {
            throw new IllegalArgumentException("not an in-flight status: " + inFlight);
        }
        moveTo(inFlight);
        this.transitionStartedAt = updatedAt;
    }

    /**
     * Re-claims a stale capture or void. The row changes, so {@code @Version} bumps, so of two
     * concurrent re-drivers exactly one commits.
     */
    public void refreshTransitionClaim() {
        if (status != PaymentStatus.CAPTURING && status != PaymentStatus.VOIDING) {
            throw new IllegalStateException("no transition in flight for a payment that is " + status);
        }
        this.updatedAt = now();
        this.transitionStartedAt = updatedAt;
    }

    public void completeTransition(PaymentStatus done) {
        if (done != PaymentStatus.CAPTURED && done != PaymentStatus.VOIDED) {
            throw new IllegalArgumentException("not a completed transition: " + done);
        }
        moveTo(done);
    }

    private void moveTo(PaymentStatus next) {
        if (!status.canMoveTo(next)) {
            throw new IllegalStateException("cannot move a payment from " + status + " to " + next);
        }
        this.status = next;
        this.updatedAt = now();
    }

    /** Postgres keeps microseconds; truncating here makes a stored snapshot equal a reload. */
    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public UUID getId() {
        return id;
    }

    public String getTenant() {
        return tenant;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public String getCardToken() {
        return cardToken;
    }

    public String getDescription() {
        return description;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public String getDeclineCode() {
        return declineCode;
    }

    public String getAcquirerAuthorizationId() {
        return acquirerAuthorizationId;
    }

    public Instant getTransitionStartedAt() {
        return transitionStartedAt;
    }

    public Long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
