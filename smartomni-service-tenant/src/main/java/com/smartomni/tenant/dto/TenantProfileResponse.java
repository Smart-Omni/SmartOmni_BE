package com.smartomni.tenant.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Detailed tenant profile and configuration response (TEN-01.1).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantProfileResponse {

    private Long id;
    private String tenantId;
    private String companyName;
    private String subdomain;
    private String status;
    private String contactEmail;
    private String contactPhone;
    private String planType;

    // Admin account details
    private Long adminUserId;
    private String adminEmail;
    private String adminFullName;

    private PlanFeatureDto features;
    private StorefrontConfigDto storefront;
    private Instant createdAt;
    private Instant updatedAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PlanFeatureDto {
        private String planName;
        private Integer maxShops;
        private Integer maxSkus;
        private boolean aiFeaturesEnabled;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StorefrontConfigDto {
        private String storeName;
        private String logoUrl;
        private String bannerUrl;
        private String primaryColor;
        private String supportPhone;
        private String address;
    }
}
