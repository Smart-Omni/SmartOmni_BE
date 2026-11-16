package com.smartomni.order.repository;

import com.smartomni.order.entity.ReturnRefundItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Transactional(readOnly = true)
public interface ReturnRefundItemRepository extends JpaRepository<ReturnRefundItem, Long> {
    List<ReturnRefundItem> findByTenantIdAndReturnRefundId(Long tenantId, Long returnRefundId);
}
