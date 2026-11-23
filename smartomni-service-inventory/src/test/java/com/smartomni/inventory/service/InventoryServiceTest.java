package com.smartomni.inventory.service;

import com.smartomni.common.exception.BusinessException;
import com.smartomni.common.tenant.TenantContext;
import com.smartomni.inventory.entity.Inventory;
import com.smartomni.inventory.entity.InventoryOutboxEvent;
import com.smartomni.inventory.entity.InventoryRestockLog;
import com.smartomni.inventory.repository.InventoryOutboxEventRepository;
import com.smartomni.inventory.repository.InventoryRepository;
import com.smartomni.inventory.repository.InventoryRestockLogRepository;
import com.smartomni.inventory.repository.SkuOwnershipRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InventoryServiceTest {
    @Mock InventoryRepository inventoryRepository;
    @Mock InventoryOutboxEventRepository outboxEventRepository;
    @Mock SkuOwnershipRepository skuOwnershipRepository;
    @Mock InventoryRestockLogRepository restockLogRepository;
    @InjectMocks InventoryService service;

    @AfterEach void clearContext() { TenantContext.clear(); }

    @Test void foreignSkuCannotChangeStockOrCreateOutbox() {
        TenantContext.setTenantId(1L);
        when(inventoryRepository.findByTenantIdAndSkuId(1L, 9L)).thenReturn(Optional.empty());
        when(skuOwnershipRepository.exists(1L, 9L)).thenReturn(false);

        assertThrows(BusinessException.class, () -> service.adjustStock(9L, 5));
        assertThrows(BusinessException.class, () -> service.restock(9L, 2));
        assertThrows(BusinessException.class, () -> service.deductStock(9L, 1));
        verify(inventoryRepository, never()).save(any());
        verifyNoInteractions(outboxEventRepository);
    }

    @Test void ownedSkuWithoutInventoryCanBeCreated() {
        TenantContext.setTenantId(1L);
        when(inventoryRepository.findByTenantIdAndSkuId(1L, 9L)).thenReturn(Optional.empty());
        when(skuOwnershipRepository.exists(1L, 9L)).thenReturn(true);
        when(skuOwnershipRepository.linkedPlatforms(1L, 9L)).thenReturn(List.of(InventoryOutboxEvent.Platform.TIKTOK_SHOP));

        service.adjustStock(9L, 5);

        verify(inventoryRepository).save(argThat(i -> i.getTenantId().equals(1L)
                && i.getSkuId().equals(9L) && i.getQuantityOnHand().equals(5)));
        verify(outboxEventRepository).save(argThat(e -> e.getPlatform() == InventoryOutboxEvent.Platform.TIKTOK_SHOP
                && e.getQuantity() == 5));
    }

    @Test void ownSkuCanBeAdjusted() {
        TenantContext.setTenantId(1L);
        Inventory inventory = new Inventory();
        inventory.setTenantId(1L);
        inventory.setSkuId(9L);
        when(inventoryRepository.findByTenantIdAndSkuId(1L, 9L)).thenReturn(Optional.of(inventory));
        when(skuOwnershipRepository.linkedPlatforms(1L, 9L)).thenReturn(List.of(InventoryOutboxEvent.Platform.TIKTOK_SHOP));

        service.adjustStock(9L, 5);

        assertEquals(5, inventory.getQuantityOnHand());
        verify(inventoryRepository).save(inventory);
        verify(outboxEventRepository).save(argThat(e -> e.getPlatform() == InventoryOutboxEvent.Platform.TIKTOK_SHOP
                && e.getQuantity() == 5));
    }

    @Test void unlinkedSkuDoesNotCreatePlatformOutboxEvent() {
        TenantContext.setTenantId(1L);
        Inventory inventory = new Inventory();
        inventory.setTenantId(1L);
        inventory.setSkuId(9L);
        when(inventoryRepository.findByTenantIdAndSkuId(1L, 9L)).thenReturn(Optional.of(inventory));

        service.adjustStock(9L, 0);

        verifyNoInteractions(outboxEventRepository);
    }

    @Test void oneEventPerLinkedPlatform() {
        TenantContext.setTenantId(1L);
        Inventory inventory = new Inventory();
        inventory.setTenantId(1L);
        inventory.setSkuId(9L);
        when(inventoryRepository.findByTenantIdAndSkuId(1L, 9L)).thenReturn(Optional.of(inventory));
        when(skuOwnershipRepository.linkedPlatforms(1L, 9L)).thenReturn(
                List.of(InventoryOutboxEvent.Platform.SHOPEE, InventoryOutboxEvent.Platform.TIKTOK_SHOP));

        service.adjustStock(9L, 0);

        verify(outboxEventRepository, times(2)).save(any());
    }

    @Test void missingTenantIsRejectedBeforeLookup() {
        assertThrows(BusinessException.class, () -> service.adjustStock(9L, 5));
        verifyNoInteractions(inventoryRepository, outboxEventRepository);
    }

    @Test void restock_withIdempotencyKey_success() {
        TenantContext.setTenantId(1L);
        when(restockLogRepository.existsByTenantIdAndIdempotencyKey(1L, "KEY-123")).thenReturn(false);
        Inventory inventory = new Inventory();
        inventory.setTenantId(1L);
        inventory.setSkuId(9L);
        inventory.setQuantityOnHand(10);
        when(inventoryRepository.findByTenantIdAndSkuId(1L, 9L)).thenReturn(Optional.of(inventory));
        when(skuOwnershipRepository.linkedPlatforms(1L, 9L)).thenReturn(List.of(InventoryOutboxEvent.Platform.TIKTOK_SHOP));

        service.restock(9L, 2, "KEY-123");

        assertEquals(12, inventory.getQuantityOnHand());
        verify(inventoryRepository).save(inventory);
        verify(restockLogRepository).save(argThat(log ->
                log.getTenantId().equals(1L) && log.getSkuId().equals(9L)
                        && log.getQuantity().equals(2) && log.getIdempotencyKey().equals("KEY-123")));
        verify(outboxEventRepository).save(argThat(e -> e.getPlatform() == InventoryOutboxEvent.Platform.TIKTOK_SHOP
                && e.getQuantity() == 12));
    }

    @Test void restock_duplicateIdempotencyKey_skipsStockIncrement() {
        TenantContext.setTenantId(1L);
        when(restockLogRepository.existsByTenantIdAndIdempotencyKey(1L, "KEY-123")).thenReturn(true);

        service.restock(9L, 2, "KEY-123");

        verify(inventoryRepository, never()).findByTenantIdAndSkuId(any(), any());
        verify(inventoryRepository, never()).save(any());
        verify(restockLogRepository, never()).save(any());
        verifyNoInteractions(outboxEventRepository);
    }

    @Test void restock_nullIdempotencyKey_incrementsNormally() {
        TenantContext.setTenantId(1L);
        Inventory inventory = new Inventory();
        inventory.setTenantId(1L);
        inventory.setSkuId(9L);
        inventory.setQuantityOnHand(5);
        when(inventoryRepository.findByTenantIdAndSkuId(1L, 9L)).thenReturn(Optional.of(inventory));
        when(skuOwnershipRepository.linkedPlatforms(1L, 9L)).thenReturn(List.of(InventoryOutboxEvent.Platform.TIKTOK_SHOP));

        service.restock(9L, 3);

        assertEquals(8, inventory.getQuantityOnHand());
        verify(inventoryRepository).save(inventory);
        verifyNoInteractions(restockLogRepository);
        verify(outboxEventRepository).save(argThat(e -> e.getPlatform() == InventoryOutboxEvent.Platform.TIKTOK_SHOP
                && e.getQuantity() == 8));
    }

    @Test
    void listEnrichedInventoryReturnsItemsFromSkuRepository() {
        TenantContext.setTenantId(1L);
        com.smartomni.common.dto.InventoryItemDto item = com.smartomni.common.dto.InventoryItemDto.builder()
                .skuId(10L)
                .skuCode("SKU-10")
                .productName("Ao Thun Polo")
                .quantityOnHand(20)
                .reservedQuantity(2)
                .availableQuantity(18)
                .build();

        when(skuOwnershipRepository.listEnrichedInventory(1L, 10, 0)).thenReturn(java.util.List.of(item));
        when(skuOwnershipRepository.countEnrichedInventory(1L)).thenReturn(1L);

        org.springframework.data.domain.Page<com.smartomni.common.dto.InventoryItemDto> page =
                service.listEnrichedInventory(org.springframework.data.domain.PageRequest.of(0, 10));

        assertNotNull(page);
        assertEquals(1, page.getTotalElements());
        assertEquals("SKU-10", page.getContent().get(0).getSkuCode());
        assertEquals("Ao Thun Polo", page.getContent().get(0).getProductName());
        assertEquals(18, page.getContent().get(0).getAvailableQuantity());
    }
}
