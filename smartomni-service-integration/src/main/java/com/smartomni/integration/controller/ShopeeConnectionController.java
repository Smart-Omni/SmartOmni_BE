package com.smartomni.integration.controller;

import com.smartomni.common.dto.ApiResponse;
import com.smartomni.integration.dto.shopee.ShopeeAuthorizationStartDto;
import com.smartomni.integration.dto.shopee.ShopeeCallbackRequest;
import com.smartomni.integration.dto.shopee.ShopeeConnectionDto;
import com.smartomni.integration.service.shopee.ShopeeConnectionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Tenant Admin endpoints for Shopee shop connection.
 * All endpoints require Tenant Admin JWT. Responses never contain tokens or secrets.
 * (Routed via Nginx /api/marketplace-connections/ to integration-service:8086).
 */
@RestController
@RequestMapping("/api/marketplace-connections/shopee")
@RequiredArgsConstructor
public class ShopeeConnectionController {

    private final ShopeeConnectionService service;

    @PostMapping("/authorization")
    public ApiResponse<ShopeeAuthorizationStartDto> start() {
        return ApiResponse.success(service.start());
    }

    /** Called by frontend callback page with code, shop_id, and state taken from redirect query string. */
    @PostMapping("/callback")
    public ApiResponse<ShopeeConnectionDto> callback(@RequestBody ShopeeCallbackRequest request) {
        return ApiResponse.success("Shopee connection updated", service.complete(request));
    }

    @GetMapping
    public ApiResponse<ShopeeConnectionDto> current() {
        return ApiResponse.success(service.current().orElse(null));
    }

    @PostMapping("/disconnect")
    public ApiResponse<ShopeeConnectionDto> disconnect() {
        return ApiResponse.success("Shopee disconnected", service.disconnect());
    }
}
