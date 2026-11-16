package com.smartomni.order.repository;

import com.smartomni.order.entity.Order;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

@org.springframework.transaction.annotation.Transactional(readOnly = true)
public interface OrderRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByIdAndTenantId(Long id, Long tenantId);

    // FR-025: chong trung lap (idempotency) - dung de kiem tra truoc khi tao moi
    Optional<Order> findByTenantIdAndPlatformAndPlatformOrderId(
            Long tenantId, Order.Platform platform, String platformOrderId);

    Optional<Order> findByTenantIdAndConnectionIdAndPlatformOrderId(
            Long tenantId, Long connectionId, String platformOrderId);

    Page<Order> findByTenantId(Long tenantId, Pageable pageable);

    // UC-06: Tra cuu don hang cheo nen tang - Khach hang tra bang Order ID, khong can dang nhap
    Optional<Order> findByPlatformAndPlatformOrderId(Order.Platform platform, String platformOrderId);
}
