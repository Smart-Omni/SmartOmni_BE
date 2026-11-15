package com.smartomni.order.repository;

import com.smartomni.order.entity.ReturnRefund;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Transactional(readOnly = true)
public interface ReturnRefundRepository extends JpaRepository<ReturnRefund, Long> {
    List<ReturnRefund> findByTenantId(Long tenantId);
    Page<ReturnRefund> findByTenantId(Long tenantId, Pageable pageable);
    Optional<ReturnRefund> findByIdAndTenantId(Long id, Long tenantId);
    Optional<ReturnRefund> findByTenantIdAndConnectionIdAndExternalReturnId(Long tenantId, Long connectionId, String externalReturnId);
    Optional<ReturnRefund> findByTenantIdAndOrderId(Long tenantId, Long orderId);
}
