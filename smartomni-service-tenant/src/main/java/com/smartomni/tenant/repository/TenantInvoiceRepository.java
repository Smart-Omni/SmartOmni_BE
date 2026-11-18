package com.smartomni.tenant.repository;

import com.smartomni.tenant.entity.TenantInvoice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TenantInvoiceRepository extends JpaRepository<TenantInvoice, Long> {

    List<TenantInvoice> findByTenantIdOrderByCreatedAtDesc(Long tenantId);
}
