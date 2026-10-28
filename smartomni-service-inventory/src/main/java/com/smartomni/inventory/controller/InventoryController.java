package com.smartomni.inventory.controller;

import com.smartomni.common.dto.ApiResponse;
import com.smartomni.inventory.service.InventoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** UC-11: Cap nhat ton kho. */
@RestController
@RequestMapping("/api/inventory")
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryService inventoryService;

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
    public ApiResponse<Void> restock(@PathVariable Long skuId, @RequestParam int quantity) {
        inventoryService.restock(skuId, quantity);
        return ApiResponse.success("Hoan tra ton kho thanh cong", null);
    }
}
