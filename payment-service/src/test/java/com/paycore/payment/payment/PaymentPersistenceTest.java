package com.paycore.payment.payment;

import com.paycore.payment.support.PaymentServiceTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Runs against real Postgres with {@code ddl-auto: validate}, so the entity and the migration must agree. */
class PaymentPersistenceTest extends PaymentServiceTest {

    @Autowired
    PaymentRepository repository;

    @Test
    void aPendingPaymentRoundTripsThroughTheSchema() {
        UUID id = UUID.randomUUID();
        Payment saved = Payment.pending(id, "acme", 1999, "USD", "tok_approve", "Order 42");
        repository.save(saved);

        Payment loaded = repository.findByIdAndTenant(id, "acme").orElseThrow();

        assertThat(loaded.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(loaded.getAmountMinor()).isEqualTo(1999);
        assertThat(loaded.getCurrency()).isEqualTo("USD");
        assertThat(loaded.getCardToken()).isEqualTo("tok_approve");
        assertThat(loaded.getDescription()).isEqualTo("Order 42");
        assertThat(loaded.getVersion()).isZero();
        assertThat(loaded.getUpdatedAt()).isEqualTo(loaded.getCreatedAt());
        assertThat(loaded.getCreatedAt()).isEqualTo(saved.getCreatedAt());
    }

    @Test
    void anotherTenantDoesNotFindIt() {
        UUID id = UUID.randomUUID();
        repository.save(Payment.pending(id, "acme", 1999, "USD", "tok_approve", null));

        assertThat(repository.findByIdAndTenant(id, "globex")).isEmpty();
    }

    @Test
    void theSchemaRefusesAnAmountOutsideTheRange() {
        assertThatThrownBy(() -> insertRaw(0, "USD", "PENDING"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theSchemaRefusesAnAmountAboveTheCap() {
        assertThatThrownBy(() -> insertRaw(1_000_000_001L, "USD", "PENDING"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theSchemaRefusesAStatusItDoesNotKnow() {
        assertThatThrownBy(() -> insertRaw(1999, "USD", "REFUNDED"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theSchemaRefusesACurrencyItDoesNotSupport() {
        assertThatThrownBy(() -> insertRaw(1999, "JPY", "PENDING"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void anIllegalTransitionIsRefusedBeforeItReachesTheDatabase() {
        Payment payment = Payment.pending(UUID.randomUUID(), "acme", 1999, "USD", "tok_approve", null);

        assertThatThrownBy(() -> payment.completeTransition(PaymentStatus.CAPTURED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PENDING");
    }

    @Test
    void completingATransitionWithANonCompletedStatusIsRejected() {
        Payment payment = Payment.pending(UUID.randomUUID(), "acme", 1999, "USD", "tok_approve", null);

        assertThatThrownBy(() -> payment.completeTransition(PaymentStatus.AUTHORIZED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void insertRaw(long amount, String currency, String status) {
        jdbc.sql("""
                        INSERT INTO payments (id, tenant, amount_minor, currency, card_token, status,
                                              version, created_at, updated_at)
                        VALUES (:id, 'acme', :amount, :currency, 'tok_approve', :status, 0, now(), now())""")
                .param("id", UUID.randomUUID())
                .param("amount", amount)
                .param("currency", currency)
                .param("status", status)
                .update();
    }
}
