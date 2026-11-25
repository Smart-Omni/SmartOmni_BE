package com.smartomni.order.controller;

import com.smartomni.common.dto.ApiResponse;
import com.smartomni.common.exception.BusinessException;
import com.smartomni.common.tenant.TenantContext;
import com.smartomni.order.dto.loyalty.ClaimPointsRequest;
import com.smartomni.order.dto.loyalty.CreateCampaignRequest;
import com.smartomni.order.dto.loyalty.CreateRewardRequest;
import com.smartomni.order.dto.loyalty.LoyaltyCampaignDto;
import com.smartomni.order.dto.loyalty.LoyaltyClaimDto;
import com.smartomni.order.dto.loyalty.LoyaltyProfileDto;
import com.smartomni.order.dto.loyalty.LoyaltyRewardRequestDto;
import com.smartomni.order.dto.loyalty.UpdateCampaignRequest;
import com.smartomni.order.service.loyalty.LoyaltyCampaignService;
import com.smartomni.order.service.loyalty.LoyaltyCustomerService;
import com.smartomni.order.service.loyalty.LoyaltyRewardService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Customer loyalty claims, cumulative points/tier, and Admin/Customer reward campaigns (WF-02).
 */
@RestController
@RequestMapping("/api/v1/loyalty")
@RequiredArgsConstructor
public class LoyaltyController {

    private final LoyaltyCustomerService customerService;
    private final LoyaltyCampaignService campaignService;
    private final LoyaltyRewardService rewardService;

    // -------------------------------------------------------------------------
    // Customer Endpoints (ROLE_CUSTOMER, tenant-scoped)
    // -------------------------------------------------------------------------

    @PostMapping("/claims")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<ApiResponse<LoyaltyClaimDto>> claimOrderPoints(
            @Valid @RequestBody ClaimPointsRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(customerService.claimOrderPoints(
                        requireUserId(), requireTenantId(), request)));
    }

    @GetMapping("/me")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<ApiResponse<LoyaltyProfileDto>> getMyProfile() {
        return ResponseEntity.ok(ApiResponse.success(
                customerService.getMyProfile(requireUserId(), requireTenantId())));
    }

    @GetMapping("/me/claims")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<ApiResponse<List<LoyaltyClaimDto>>> getMyClaims() {
        return ResponseEntity.ok(ApiResponse.success(
                customerService.getMyClaims(requireUserId(), requireTenantId())));
    }

    @PostMapping("/me/reward-requests")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<ApiResponse<LoyaltyRewardRequestDto>> requestReward(
            @Valid @RequestBody CreateRewardRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success(rewardService.requestReward(
                        requireUserId(), requireTenantId(), request)));
    }

    @GetMapping("/me/vouchers")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<ApiResponse<List<LoyaltyRewardRequestDto>>> getMyVouchers() {
        return ResponseEntity.ok(ApiResponse.success(
                rewardService.listMyVouchers(requireUserId(), requireTenantId())));
    }

    // -------------------------------------------------------------------------
    // Campaigns (Shared GET by role; Admin POST/PATCH & reward-requests view)
    // -------------------------------------------------------------------------

    @GetMapping("/campaigns")
    @PreAuthorize("hasAnyRole('CUSTOMER', 'TENANT_ADMIN', 'MANAGER', 'ADMIN')")
    public ResponseEntity<ApiResponse<List<LoyaltyCampaignDto>>> listCampaigns() {
        Long tenantId = requireTenantId();
        if (isCustomerRole()) {
            return ResponseEntity.ok(ApiResponse.success(
                    campaignService.listActiveCampaignsForCustomer(tenantId)));
        }
        return ResponseEntity.ok(ApiResponse.success(
                campaignService.listAdminCampaigns(tenantId)));
    }

    @PostMapping("/campaigns")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN', 'MANAGER', 'ADMIN')")
    public ResponseEntity<ApiResponse<LoyaltyCampaignDto>> createCampaign(
            @Valid @RequestBody CreateCampaignRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(campaignService.createCampaign(
                        requireUserId(), requireTenantId(), request)));
    }

    @PatchMapping("/campaigns/{id}")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN', 'MANAGER', 'ADMIN')")
    public ResponseEntity<ApiResponse<LoyaltyCampaignDto>> updateCampaign(
            @PathVariable Long id,
            @RequestBody UpdateCampaignRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                campaignService.updateCampaign(requireTenantId(), id, request)));
    }

    @GetMapping("/reward-requests")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN', 'MANAGER', 'ADMIN')")
    public ResponseEntity<ApiResponse<List<LoyaltyRewardRequestDto>>> listRewardRequests() {
        return ResponseEntity.ok(ApiResponse.success(
                campaignService.listAdminRewardRequests(requireTenantId())));
    }

    private static Long requireTenantId() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new BusinessException("Authenticated tenant context is required",
                    HttpStatus.FORBIDDEN, "TENANT_MISSING");
        }
        return tenantId;
    }

    private static Long requireUserId() {
        Long userId = TenantContext.getCurrentUserId();
        if (userId != null) {
            return userId;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Long id) {
            return id;
        }
        throw new BusinessException("Authenticated userId is required",
                HttpStatus.FORBIDDEN, "USER_ID_MISSING");
    }

    private static boolean isCustomerRole() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getAuthorities() == null) {
            return false;
        }
        return auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_CUSTOMER".equalsIgnoreCase(a.getAuthority()));
    }
}
