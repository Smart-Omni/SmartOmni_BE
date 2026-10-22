package com.smartomni.tenant.controller;

import com.smartomni.common.dto.ApiResponse;
import com.smartomni.common.exception.BusinessException;
import com.smartomni.common.tenant.TenantContext;
import com.smartomni.tenant.dto.LockTenantRequest;
import com.smartomni.tenant.dto.PublicTenantStorefrontResponse;
import com.smartomni.tenant.dto.StorefrontConfigRequest;
import com.smartomni.tenant.dto.SubdomainCheckResponse;
import com.smartomni.tenant.dto.TenantOnboardingRequest;
import com.smartomni.tenant.dto.TenantProfileResponse;
import com.smartomni.tenant.entity.Tenant;
import com.smartomni.tenant.service.TenantService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.*;

/**
 * Controller for Tenant operations (TEN-01.1, UC-17, UC-19, UC-25).
 * Supports both /api/v1/tenants and /api/tenants paths.
 */
@RestController
@RequestMapping({"/api/v1/tenants", "/api/tenants"})
@RequiredArgsConstructor
public class TenantController {

    private final TenantService tenantService;

    @GetMapping("/check-subdomain")
    public ApiResponse<SubdomainCheckResponse> checkSubdomain(@RequestParam String subdomain) {
        return ApiResponse.success(tenantService.checkSubdomain(subdomain));
    }

    @GetMapping("/public/by-subdomain/{subdomain}")
    public ApiResponse<PublicTenantStorefrontResponse> getPublicStorefront(@PathVariable String subdomain) {
        return ApiResponse.success(tenantService.getPublicStorefrontProfile(subdomain));
    }

    @PostMapping("/onboarding")
    public ApiResponse<TenantProfileResponse> onboard(@Valid @RequestBody TenantOnboardingRequest request) {
        return ApiResponse.success("Khởi tạo Tenant thành công", tenantService.onboardTenant(request));
    }

    @GetMapping("/me")
    public ApiResponse<TenantProfileResponse> getCurrentTenant() {
        return ApiResponse.success(tenantService.getCurrentTenantProfile());
    }

    @PutMapping("/storefront")
    public ApiResponse<TenantProfileResponse> updateStorefront(@Valid @RequestBody StorefrontConfigRequest request) {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new BusinessException("Không tìm thấy thông tin tenant trong context");
        }
        return ApiResponse.success("Cập nhật storefront thành công", tenantService.updateStorefront(tenantId, request));
    }

    @GetMapping
    public ApiResponse<Page<Tenant>> listTenants(@RequestParam(required = false) String keyword, Pageable pageable) {
        return ApiResponse.success(tenantService.searchTenants(keyword, pageable));
    }

    @PostMapping("/{tenantId}/lock")
    public ApiResponse<Void> lock(@PathVariable Long tenantId, @Valid @RequestBody LockTenantRequest request) {
        tenantService.lockTenant(tenantId, request.getReason(), TenantContext.getCurrentUserId());
        return ApiResponse.success("Đã khóa Tenant", null);
    }

    @PostMapping("/{tenantId}/unlock")
    public ApiResponse<Void> unlock(@PathVariable Long tenantId) {
        tenantService.unlockTenant(tenantId);
        return ApiResponse.success("Đã mở khóa Tenant", null);
    }

    @GetMapping("/{tenantId}/status")
    public ApiResponse<Boolean> isActive(@PathVariable Long tenantId) {
        return ApiResponse.success(tenantService.isTenantActive(tenantId));
    }
}
