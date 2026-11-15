package com.smartomni.inventory.controller;

import com.smartomni.common.dto.ApiResponse;
import com.smartomni.inventory.service.InventoryService;
import com.smartomni.inventory.entity.Inventory;
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

    @GetMapping
    public ApiResponse<Page<Inventory>> list(Pageable pageable) {
        return ApiResponse.success(inventoryService.listInventory(pageable));
    }

    @PostMapping("/{skuId}/adjust")
    public ApiResponse<Void> adjust(@PathVariable Long skuId, @RequestParam int quantity) {
        inventoryService.adjustStock(skuId, quantity);
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
