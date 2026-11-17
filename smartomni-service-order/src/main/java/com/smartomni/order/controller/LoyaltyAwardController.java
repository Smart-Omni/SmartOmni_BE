package com.smartomni.order.controller;

import com.smartomni.common.dto.ApiResponse;
import com.smartomni.order.dto.LoyaltyAwardResultDto;
import com.smartomni.order.service.LoyaltyAwardService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin/Staff endpoint to manually trigger the 15-day loyalty point award job for E2E verification (WF-02 Part B).
 */
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('TENANT_ADMIN', 'STAFF')")
public class LoyaltyAwardController {

    private final LoyaltyAwardService loyaltyAwardService;

    @PostMapping({"/api/v1/loyalty/points/award-eligible", "/api/v1/orders/loyalty/award-eligible"})
    public ResponseEntity<ApiResponse<LoyaltyAwardResultDto>> awardEligiblePoints() {
        LoyaltyAwardResultDto result = loyaltyAwardService.awardEligiblePointsForCurrentTenant();
        return ResponseEntity.ok(ApiResponse.success("Eligible loyalty points awarded", result));
    }
}
