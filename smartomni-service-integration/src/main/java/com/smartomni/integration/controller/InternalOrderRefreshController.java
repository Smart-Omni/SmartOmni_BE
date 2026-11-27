package com.smartomni.integration.controller;

import com.smartomni.common.dto.ApiResponse;
import com.smartomni.common.tenant.TenantContext;
import com.smartomni.integration.service.tiktok.TiktokOrderRefreshService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Service-to-service only (internal JWT; nginx returns 404 for /internal/). The tenant comes from that token. */
@RestController
@RequestMapping("/internal/integrations/orders")
@RequiredArgsConstructor
public class InternalOrderRefreshController {

    private final TiktokOrderRefreshService refreshService;

    @PostMapping("/tiktok/refresh")
    @PreAuthorize("hasRole('INTERNAL_SERVICE')")
    public ApiResponse<Map<String, String>> refresh(@RequestBody Map<String, String> body) {
        return ApiResponse.success(refreshService.refresh(TenantContext.getTenantId(), body.get("platformOrderId")));
    }
}
