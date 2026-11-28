package com.smartomni.order.repository;

import com.smartomni.order.entity.Order;
import com.smartomni.order.entity.OrderFinanceSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OrderFinanceSnapshotRepository extends JpaRepository<OrderFinanceSnapshot, Long> {

    Optional<OrderFinanceSnapshot> findByTenantIdAndPlatformAndPlatformOrderId(
            Long tenantId, Order.Platform platform, String platformOrderId);
}
