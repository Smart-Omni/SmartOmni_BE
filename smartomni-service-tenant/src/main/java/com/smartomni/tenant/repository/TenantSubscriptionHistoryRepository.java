package com.smartomni.tenant.repository;

import com.smartomni.tenant.entity.TenantSubscriptionHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TenantSubscriptionHistoryRepository extends JpaRepository<TenantSubscriptionHistory, Long> {

    List<TenantSubscriptionHistory> findByTenantIdOrderByChangedAtDesc(Long tenantId);
}
