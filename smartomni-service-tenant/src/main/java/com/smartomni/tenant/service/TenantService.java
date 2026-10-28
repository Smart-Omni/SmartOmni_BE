package com.smartomni.tenant.service;

import com.smartomni.tenant.dto.StorefrontConfigRequest;
import com.smartomni.tenant.dto.SubdomainCheckResponse;
import com.smartomni.tenant.dto.TenantOnboardingRequest;
import com.smartomni.tenant.dto.TenantProfileResponse;
import com.smartomni.tenant.entity.Tenant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Service interface for Tenant management (TEN-01.1, UC-17, UC-19, UC-25).
 */
public interface TenantService {

    SubdomainCheckResponse checkSubdomain(String subdomain);

    TenantProfileResponse onboardTenant(TenantOnboardingRequest request);

    default TenantProfileResponse createTenant(TenantOnboardingRequest request) {
        return onboardTenant(request);
    }

    TenantProfileResponse getTenantProfile(Long tenantId);

    TenantProfileResponse getCurrentTenantProfile();

    TenantProfileResponse updateStorefront(Long tenantId, StorefrontConfigRequest request);

    Page<Tenant> searchTenants(String keyword, Pageable pageable);

    void lockTenant(Long tenantId, String reason, Long lockedByUserId);

    void unlockTenant(Long tenantId);

    Tenant getTenantOrThrow(Long tenantId);

    boolean isTenantActive(Long tenantId);

    com.smartomni.tenant.dto.PublicTenantStorefrontResponse getPublicStorefrontProfile(String subdomain);
}
