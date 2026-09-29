package com.paycore.payment.payment;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    /** The only way a caller-facing path loads a payment: another tenant's id is simply absent. */
    Optional<Payment> findByIdAndTenant(UUID id, String tenant);

    /** Served by payments_tenant_created_idx. Keyset pagination arrives in M7. */
    List<Payment> findByTenantOrderByCreatedAtDescIdDesc(String tenant, Limit limit);
}
