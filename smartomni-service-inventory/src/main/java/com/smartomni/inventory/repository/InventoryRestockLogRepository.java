package com.smartomni.inventory.repository;

import com.smartomni.inventory.entity.InventoryRestockLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Transactional(readOnly = true)
public interface InventoryRestockLogRepository extends JpaRepository<InventoryRestockLog, Long> {
    boolean existsByTenantIdAndIdempotencyKey(Long tenantId, String idempotencyKey);
    Optional<InventoryRestockLog> findByTenantIdAndIdempotencyKey(Long tenantId, String idempotencyKey);
}
