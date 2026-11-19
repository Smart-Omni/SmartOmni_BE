package com.smartomni.inventory.controller;

import com.smartomni.common.dto.ApiResponse;
import com.smartomni.common.dto.InventoryItemDto;
import com.smartomni.inventory.scheduler.OutboxPublisherScheduler;
import com.smartomni.inventory.service.InventoryService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** UC-11: Cap nhat ton kho. */
@RestController
@RequestMapping("/api/v1/inventory")
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryService inventoryService;
    private final OutboxPublisherScheduler outboxPublisherScheduler;

    @GetMapping
    public ApiResponse<Page<InventoryItemDto>> list(Pageable pageable) {
        return ApiResponse.success(inventoryService.listEnrichedInventory(pageable));
    }

    @PostMapping("/{skuId}/adjust")
    public ApiResponse<Void> adjust(@PathVariable Long skuId, @RequestParam int quantity) {
        inventoryService.adjustStock(skuId, quantity);
        try {
            outboxPublisherScheduler.publishPendingEvents();
        } catch (Exception ignored) {
            // Background quartz worker will retry if instantaneous push fails
        }
        return ApiResponse.success("Cap nhat ton kho thanh cong", null);
    }

    @PostMapping("/{skuId}/deduct")
    public ApiResponse<Void> deduct(@PathVariable Long skuId, @RequestParam int quantity) {
        inventoryService.deductStock(skuId, quantity);
        return ApiResponse.success("Tru ton kho thanh cong", null);
    }

    @PostMapping("/{skuId}/restock")
    public ApiResponse<Void> restock(
            @PathVariable Long skuId,
            @RequestParam int quantity,
            @RequestParam(value = "idempotencyKey", required = false) String idempotencyKey) {
        inventoryService.restock(skuId, quantity, idempotencyKey);
        return ApiResponse.success("Hoan tra ton kho thanh cong", null);
    }
}
