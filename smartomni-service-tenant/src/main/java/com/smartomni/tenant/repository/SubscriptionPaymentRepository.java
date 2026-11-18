package com.smartomni.tenant.repository;

import com.smartomni.tenant.entity.PaymentStatus;
import com.smartomni.tenant.entity.SubscriptionPayment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SubscriptionPaymentRepository extends JpaRepository<SubscriptionPayment, Long> {

    Optional<SubscriptionPayment> findByGatewayTransactionId(String gatewayTransactionId);

    Optional<SubscriptionPayment> findByIdAndTenantId(Long id, Long tenantId);

    Optional<SubscriptionPayment> findFirstByTenantIdAndStatusOrderByCreatedAtDesc(Long tenantId, PaymentStatus status);

    Optional<SubscriptionPayment> findFirstByInvoiceIdAndStatusOrderByCreatedAtDesc(Long invoiceId, PaymentStatus status);
}
