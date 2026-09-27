package com.paycore.payment.payment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    /** The only way a caller-facing path loads a payment: another tenant's id is simply absent. */
    Optional<Payment> findByIdAndTenant(UUID id, String tenant);
}
