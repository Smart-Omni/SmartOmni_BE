package com.smartomni.order.client;

import com.smartomni.common.dto.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** Goi sang smartomni-service-inventory de tru / hoan tra ton kho (UC-33). */
@FeignClient(name = "smartomni-service-inventory", url = "${smartomni.services.inventory-url:http://localhost:8084}", configuration = com.smartomni.order.config.InventoryClientSecurityConfig.class)
public interface InventoryServiceClient {

    @PostMapping("/api/v1/inventory/{skuId}/deduct")
    ApiResponse<Void> deductStock(@PathVariable("skuId") Long skuId, @RequestParam("quantity") int quantity);

    @PostMapping("/api/v1/inventory/{skuId}/restock")
    ApiResponse<Void> restock(
            @PathVariable("skuId") Long skuId,
            @RequestParam("quantity") int quantity,
            @RequestParam(value = "idempotencyKey", required = false) String idempotencyKey);

    default ApiResponse<Void> restock(Long skuId, int quantity) {
        return restock(skuId, quantity, null);
    }
}
