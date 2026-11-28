package com.smartomni.ai.controller;

import com.smartomni.ai.dto.insights.SalesInsightsResponse;
import com.smartomni.ai.service.SalesInsightsService;
import com.smartomni.common.dto.ApiResponse;
import com.smartomni.common.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Deterministic multi-channel sales & deductions comparison endpoint for "So sánh doanh thu".
 */
@RestController
@RequestMapping("/api/v1/ai/insights")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('TENANT_ADMIN', 'MANAGER')")
public class SalesInsightsController {

    private final SalesInsightsService salesInsightsService;

    @GetMapping("/sales")
    public ApiResponse<SalesInsightsResponse> getSalesInsights(
            @RequestParam(defaultValue = "7") int periodDays) {
        return ApiResponse.success(salesInsightsService.getSalesInsights(TenantContext.getTenantId(), periodDays));
    }
}
