package com.smartomni.tenant.repository;

import com.smartomni.tenant.entity.InvoiceStatus;
import com.smartomni.tenant.entity.TenantInvoice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TenantInvoiceRepository extends JpaRepository<TenantInvoice, Long> {

    List<TenantInvoice> findByTenantIdOrderByCreatedAtDesc(Long tenantId);

    Optional<TenantInvoice> findFirstByTenantIdAndStatusOrderByCreatedAtDesc(Long tenantId, InvoiceStatus status);
}
