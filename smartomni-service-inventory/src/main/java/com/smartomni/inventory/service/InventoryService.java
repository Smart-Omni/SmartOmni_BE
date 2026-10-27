package com.smartomni.inventory.service;

import com.smartomni.common.exception.BusinessException;
import com.smartomni.common.tenant.TenantContext;
import com.smartomni.inventory.entity.Inventory;
import com.smartomni.inventory.entity.InventoryOutboxEvent;
import com.smartomni.inventory.repository.InventoryOutboxEventRepository;
import com.smartomni.inventory.repository.InventoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * UC-11: Cap nhat ton kho - FR-026, FR-027, FR-028
 * UC-40: Ghi Outbox event trong CUNG transaction voi cap nhat ton kho - FR-074
 */
@Service
@RequiredArgsConstructor
public class InventoryService {

    private final InventoryRepository inventoryRepository;
    private final InventoryOutboxEventRepository outboxEventRepository;

    /**
     * Tru ton kho khi co don hang moi (goi tu Order Service qua REST/MQ consumer).
     * @Retryable: neu OptimisticLockingFailureException xay ra do 2 giao dich cung
     * tru kho dong thoi, tu dong thu lai theo FR-027.
     */
    @Retryable(retryFor = OptimisticLockingFailureException.class, maxAttempts = 3, backoff = @Backoff(delay = 100))
    @Transactional
    public void deductStock(Long skuId, int quantity) {
        Inventory inventory = inventoryRepository.findBySkuId(skuId)
                .orElseThrow(() -> new BusinessException("Khong tim thay ton kho cho SKU: " + skuId));

        if (inventory.getQuantityOnHand() < quantity) {
            throw new BusinessException("Khong du ton kho cho SKU: " + skuId);
        }

        inventory.setQuantityOnHand(inventory.getQuantityOnHand() - quantity);
        inventoryRepository.save(inventory); // JPA @Version tu dong kiem tra optimistic lock

        // FR-028, FR-074: ghi Outbox event trong CUNG transaction de dam bao khong mat du lieu
        createOutboxEventsForAllLinkedPlatforms(inventory);
    }

    @Transactional
    public void adjustStock(Long skuId, int newQuantity) {
        Long tenantId = TenantContext.getTenantId();
        Inventory inventory = inventoryRepository.findBySkuId(skuId)
                .orElseGet(() -> {
                    Inventory inv = new Inventory();
                    inv.setTenantId(tenantId);
                    inv.setSkuId(skuId);
                    return inv;
                });
        inventory.setQuantityOnHand(newQuantity);
        inventoryRepository.save(inventory);

        createOutboxEventsForAllLinkedPlatforms(inventory);
    }

    @Transactional
    public void restock(Long skuId, int quantity) {
        Long tenantId = TenantContext.getTenantId();
        Inventory inventory = inventoryRepository.findBySkuId(skuId)
                .orElseGet(() -> {
                    Inventory inv = new Inventory();
                    inv.setTenantId(tenantId);
                    inv.setSkuId(skuId);
                    inv.setQuantityOnHand(0);
                    return inv;
                });
        inventory.setQuantityOnHand(inventory.getQuantityOnHand() + quantity);
        inventoryRepository.save(inventory);

        createOutboxEventsForAllLinkedPlatforms(inventory);
    }

    private void createOutboxEventsForAllLinkedPlatforms(Inventory inventory) {
        // TODO: goi Catalog Service de lay danh sach platform da lien ket voi SKU nay,
        // sau do tao 1 InventoryOutboxEvent (status=PENDING) cho MOI platform.
        // Vi du minh hoa cho 1 platform:
        InventoryOutboxEvent event = new InventoryOutboxEvent();
        event.setTenantId(inventory.getTenantId());
        event.setSkuId(inventory.getSkuId());
        event.setPlatform(InventoryOutboxEvent.Platform.SHOPEE);
        event.setQuantity(inventory.getQuantityOnHand());
        outboxEventRepository.save(event);
    }
}
