package com.smartomni.inventory.repository;

import com.smartomni.inventory.entity.InventoryOutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

@org.springframework.transaction.annotation.Transactional(readOnly = true)
public interface InventoryOutboxEventRepository extends JpaRepository<InventoryOutboxEvent, Long> {
    List<InventoryOutboxEvent> findTop100ByStatusOrderByCreatedAtAsc(InventoryOutboxEvent.OutboxStatus status);
}
