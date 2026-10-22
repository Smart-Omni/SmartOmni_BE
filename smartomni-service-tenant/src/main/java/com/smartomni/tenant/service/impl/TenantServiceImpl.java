package com.smartomni.tenant.service.impl;

import com.smartomni.common.exception.BusinessException;
import com.smartomni.common.exception.ResourceNotFoundException;
import com.smartomni.common.tenant.TenantContext;
import com.smartomni.common.tenant.TenantProperties;
import com.smartomni.tenant.dto.PublicTenantStorefrontResponse;
import com.smartomni.tenant.dto.StorefrontConfigRequest;
import com.smartomni.tenant.dto.SubdomainCheckResponse;
import com.smartomni.tenant.dto.TenantOnboardingRequest;
import com.smartomni.tenant.dto.TenantProfileResponse;
import com.smartomni.tenant.entity.PlanFeatureFlag;
import com.smartomni.tenant.entity.SubscriptionPlan;
import com.smartomni.tenant.entity.Tenant;
import com.smartomni.tenant.repository.PlanFeatureFlagRepository;
import com.smartomni.tenant.repository.SubscriptionPlanRepository;
import com.smartomni.tenant.repository.TenantRepository;
import com.smartomni.tenant.service.TenantService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implementation of TenantService (TEN-01.1, UC-17, UC-19, UC-25).
 */
@Service
@RequiredArgsConstructor
public class TenantServiceImpl implements TenantService {

    private final TenantRepository tenantRepository;
    private final SubscriptionPlanRepository subscriptionPlanRepository;
    private final PlanFeatureFlagRepository featureFlagRepository;
    private final org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate;
    private final TenantProperties tenantProperties;

    // In-memory persistent cache for tenant storefront configurations
    private final Map<Long, StorefrontConfigRequest> storefrontConfigMap = new ConcurrentHashMap<>();
    // In-memory cache for tenant contact details
    private final Map<Long, String> contactEmailMap = new ConcurrentHashMap<>();
    private final Map<Long, String> contactPhoneMap = new ConcurrentHashMap<>();

    @Override
    public SubdomainCheckResponse checkSubdomain(String subdomain) {
        if (subdomain == null || subdomain.trim().isEmpty()) {
            return SubdomainCheckResponse.builder()
                    .subdomain(subdomain)
                    .available(false)
                    .message("Subdomain không được để trống")
                    .build();
        }

        String clean = subdomain.trim().toLowerCase();
        if (clean.length() < 3 || clean.length() > 30) {
            return SubdomainCheckResponse.builder()
                    .subdomain(clean)
                    .available(false)
                    .message("Độ dài subdomain phải từ 3 đến 30 ký tự")
                    .build();
        }

        if (!clean.matches("^[a-z0-9-]+$")) {
            return SubdomainCheckResponse.builder()
                    .subdomain(clean)
                    .available(false)
                    .message("Subdomain chỉ chứa ký tự chữ thường, số và dấu gạch ngang")
                    .build();
        }

        Set<String> reservedKeywords = (tenantProperties != null && tenantProperties.getReservedSubdomains() != null)
                ? tenantProperties.getReservedSubdomains()
                : Set.of("admin", "api", "auth", "app", "www", "static", "mail");

        for (String keyword : reservedKeywords) {
            if (clean.contains(keyword)) {
                return SubdomainCheckResponse.builder()
                        .subdomain(clean)
                        .available(false)
                        .message("Subdomain chứa từ khóa cấm của hệ thống: " + keyword)
                        .build();
            }
        }

        if (tenantRepository.existsBySubdomain(clean)) {
            return SubdomainCheckResponse.builder()
                    .subdomain(clean)
                    .available(false)
                    .message("Subdomain đã được sử dụng")
                    .build();
        }

        return SubdomainCheckResponse.builder()
                .subdomain(clean)
                .available(true)
                .message("Subdomain khả dụng")
                .build();
    }

    @Override
    @Transactional
    public TenantProfileResponse onboardTenant(TenantOnboardingRequest request) {
        SubdomainCheckResponse check = checkSubdomain(request.getSubdomain());
        if (!check.isAvailable()) {
            throw new BusinessException(check.getMessage());
        }

        String planTypeStr = request.getPlanType() != null ? request.getPlanType().trim().toUpperCase() : "FREE";
        boolean isPro = "PRO".equals(planTypeStr);
        String normalizedPlanName = isPro ? "PRO" : "FREE";

        // Resolve or create SaaS Subscription Plan
        SubscriptionPlan plan = subscriptionPlanRepository.findByName(normalizedPlanName)
                .or(() -> subscriptionPlanRepository.findByName(isPro ? "Pro" : "Free"))
                .orElseGet(() -> {
                    SubscriptionPlan newPlan = new SubscriptionPlan();
                    newPlan.setName(normalizedPlanName);
                    if (isPro) {
                        newPlan.setPrice(new BigDecimal("499000.00"));
                        newPlan.setMaxMarketplaceConnections(Integer.MAX_VALUE);
                        newPlan.setMaxSkus(Integer.MAX_VALUE);
                        newPlan.setStorageLimitMb(10240);
                    } else {
                        newPlan.setPrice(BigDecimal.ZERO);
                        newPlan.setMaxMarketplaceConnections(1);
                        newPlan.setMaxSkus(100);
                        newPlan.setStorageLimitMb(1024);
                    }
                    return subscriptionPlanRepository.save(newPlan);
                });

        // Set Plan Feature Flags: AI Features enabled for PRO, disabled for FREE
        featureFlagRepository.findByPlanIdAndFeatureKey(plan.getId(), "ai_features")
                .orElseGet(() -> {
                    PlanFeatureFlag flag = new PlanFeatureFlag();
                    flag.setPlanId(plan.getId());
                    flag.setFeatureKey("ai_features");
                    flag.setEnabled(isPro);
                    return featureFlagRepository.save(flag);
                });

        String company = request.getCompanyName() != null && !request.getCompanyName().isBlank()
                ? request.getCompanyName()
                : request.getSubdomain();

        Tenant tenant = new Tenant();
        tenant.setName(company);
        tenant.setSubdomain(request.getSubdomain().trim().toLowerCase());
        tenant.setStatus(Tenant.TenantStatus.ACTIVE);
        tenant.setPlanId(plan.getId());
        tenant.setTenantId(UUID.randomUUID().toString());
        tenant.setContactEmail(request.getContactEmail());
        tenant.setContactPhone(request.getContactPhone());
        tenant.setPlanType(normalizedPlanName);
        tenant.setStoreName(company);
        tenant.setMaxMarketplaceConnections(isPro ? Integer.MAX_VALUE : 1);
        tenant.setMaxSkus(isPro ? Integer.MAX_VALUE : 100);
        tenant.setAiFeaturesEnabled(isPro);

        tenant = tenantRepository.save(tenant);

        if (request.getContactEmail() != null) {
            contactEmailMap.put(tenant.getId(), request.getContactEmail());
        }
        if (request.getContactPhone() != null) {
            contactPhoneMap.put(tenant.getId(), request.getContactPhone());
        }

        StorefrontConfigRequest defaultStorefront = StorefrontConfigRequest.builder()
                .storeName(company)
                .supportPhone(request.getContactPhone())
                .build();
        storefrontConfigMap.put(tenant.getId(), defaultStorefront);

        return buildProfileResponse(tenant);
    }

    @Override
    public TenantProfileResponse getCurrentTenantProfile() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new BusinessException("Không tìm thấy thông tin tenant trong context");
        }
        return getTenantProfile(tenantId);
    }

    @Override
    public TenantProfileResponse getTenantProfile(Long tenantId) {
        Tenant tenant = getTenantOrThrow(tenantId);
        return buildProfileResponse(tenant);
    }

    @Override
    @Transactional
    public TenantProfileResponse updateStorefront(Long tenantId, StorefrontConfigRequest request) {
        Tenant tenant = getTenantOrThrow(tenantId);

        tenant.setStoreName(request.getStoreName());
        tenant.setLogoUrl(request.getLogoUrl());
        tenant.setBannerUrl(request.getBannerUrl());
        tenant.setPrimaryColor(request.getPrimaryColor());
        tenant.setSupportPhone(request.getSupportPhone());
        tenant.setAddress(request.getAddress());

        storefrontConfigMap.put(tenantId, request);
        tenantRepository.save(tenant);

        return buildProfileResponse(tenant);
    }

    @Override
    public Page<Tenant> searchTenants(String keyword, Pageable pageable) {
        String kw = keyword == null ? "" : keyword;
        return tenantRepository.findByNameContainingIgnoreCaseOrSubdomainContainingIgnoreCase(kw, kw, pageable);
    }

    @Override
    @Transactional
    public void lockTenant(Long tenantId, String reason, Long lockedByUserId) {
        Tenant tenant = getTenantOrThrow(tenantId);
        tenant.setStatus(Tenant.TenantStatus.LOCKED);
        tenant.setLockedReason(reason);
        tenant.setLockedBy(lockedByUserId);
        tenant.setLockedAt(Instant.now());
        tenantRepository.save(tenant);

        // Ghi cờ auth:tenant_locked:<tenantId> lên Redis để lập tức vô hiệu hóa mọi phiên truy cập của Tenant
        if (stringRedisTemplate != null) {
            stringRedisTemplate.opsForValue().set(
                    com.smartomni.common.constant.AppConstants.REDIS_PREFIX_TENANT_LOCKED + tenantId,
                    "LOCKED"
            );
        }
    }

    @Override
    @Transactional
    public void unlockTenant(Long tenantId) {
        Tenant tenant = getTenantOrThrow(tenantId);
        tenant.setStatus(Tenant.TenantStatus.ACTIVE);
        tenant.setLockedReason(null);
        tenant.setLockedBy(null);
        tenant.setLockedAt(null);
        tenantRepository.save(tenant);

        // Xóa cờ auth:tenant_locked:<tenantId> khỏi Redis
        if (stringRedisTemplate != null) {
            stringRedisTemplate.delete(com.smartomni.common.constant.AppConstants.REDIS_PREFIX_TENANT_LOCKED + tenantId);
        }
    }

    @Override
    public Tenant getTenantOrThrow(Long tenantId) {
        return tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant không tồn tại: " + tenantId));
    }

    @Override
    public boolean isTenantActive(Long tenantId) {
        return tenantRepository.findById(tenantId)
                .map(t -> t.getStatus() == Tenant.TenantStatus.ACTIVE)
                .orElse(false);
    }

    private TenantProfileResponse buildProfileResponse(Tenant tenant) {
        String planName = tenant.getPlanType() != null ? tenant.getPlanType().toUpperCase() : "FREE";
        int maxShops = "PRO".equalsIgnoreCase(planName) ? Integer.MAX_VALUE : 1;
        int maxSkus = "PRO".equalsIgnoreCase(planName) ? Integer.MAX_VALUE : 100;
        boolean aiEnabled = "PRO".equalsIgnoreCase(planName);

        if (tenant.getPlanId() != null) {
            SubscriptionPlan plan = subscriptionPlanRepository.findById(tenant.getPlanId()).orElse(null);
            if (plan != null) {
                if (plan.getName() != null) {
                    planName = plan.getName().toUpperCase();
                }
                if (plan.getMaxMarketplaceConnections() != null) {
                    maxShops = plan.getMaxMarketplaceConnections();
                }
                if (plan.getMaxSkus() != null) {
                    maxSkus = plan.getMaxSkus();
                }
            }
            aiEnabled = featureFlagRepository.findByPlanIdAndFeatureKey(tenant.getPlanId(), "ai_features")
                    .map(PlanFeatureFlag::isEnabled)
                    .orElse("PRO".equalsIgnoreCase(planName));
        }

        StorefrontConfigRequest storefrontConfig = storefrontConfigMap.get(tenant.getId());
        TenantProfileResponse.StorefrontConfigDto storefrontDto;
        if (storefrontConfig != null) {
            storefrontDto = TenantProfileResponse.StorefrontConfigDto.builder()
                    .storeName(storefrontConfig.getStoreName())
                    .logoUrl(storefrontConfig.getLogoUrl())
                    .bannerUrl(storefrontConfig.getBannerUrl())
                    .primaryColor(storefrontConfig.getPrimaryColor())
                    .supportPhone(storefrontConfig.getSupportPhone())
                    .address(storefrontConfig.getAddress())
                    .build();
        } else {
            storefrontDto = TenantProfileResponse.StorefrontConfigDto.builder()
                    .storeName(tenant.getStoreName() != null ? tenant.getStoreName() : tenant.getName())
                    .logoUrl(tenant.getLogoUrl())
                    .bannerUrl(tenant.getBannerUrl())
                    .primaryColor(tenant.getPrimaryColor())
                    .supportPhone(tenant.getSupportPhone())
                    .address(tenant.getAddress())
                    .build();
        }

        String contactEmail = tenant.getContactEmail() != null
                ? tenant.getContactEmail()
                : contactEmailMap.get(tenant.getId());
        String contactPhone = tenant.getContactPhone() != null
                ? tenant.getContactPhone()
                : contactPhoneMap.get(tenant.getId());

        return TenantProfileResponse.builder()
                .id(tenant.getId())
                .tenantId(tenant.getTenantId())
                .companyName(tenant.getName())
                .subdomain(tenant.getSubdomain())
                .status(tenant.getStatus() != null ? tenant.getStatus().name() : "ACTIVE")
                .contactEmail(contactEmail)
                .contactPhone(contactPhone)
                .planType(planName)
                .features(TenantProfileResponse.PlanFeatureDto.builder()
                        .planName(planName)
                        .maxShops(maxShops)
                        .maxSkus(maxSkus)
                        .aiFeaturesEnabled(aiEnabled)
                        .build())
                .storefront(storefrontDto)
                .createdAt(tenant.getCreatedAt())
                .updatedAt(tenant.getUpdatedAt())
                .build();
    }

    @Override
    public PublicTenantStorefrontResponse getPublicStorefrontProfile(String subdomain) {
        if (subdomain == null || subdomain.trim().isEmpty()) {
            throw new BusinessException("Subdomain không được để trống");
        }

        String clean = subdomain.trim().toLowerCase();
        Tenant tenant = tenantRepository.findBySubdomain(clean)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy cửa hàng với subdomain: " + clean));

        if (tenant.getStatus() == Tenant.TenantStatus.LOCKED) {
            throw new BusinessException("Cửa hàng hiện đang tạm ngưng hoạt động");
        }

        StorefrontConfigRequest storefrontConfig = storefrontConfigMap.get(tenant.getId());
        String storeName = tenant.getStoreName() != null ? tenant.getStoreName() : tenant.getName();
        String logoUrl = tenant.getLogoUrl();
        String bannerUrl = tenant.getBannerUrl();
        String primaryColor = tenant.getPrimaryColor();
        String supportPhone = tenant.getSupportPhone();
        String address = tenant.getAddress();

        if (storefrontConfig != null) {
            if (storefrontConfig.getStoreName() != null && !storefrontConfig.getStoreName().isBlank()) {
                storeName = storefrontConfig.getStoreName();
            }
            if (storefrontConfig.getLogoUrl() != null) {
                logoUrl = storefrontConfig.getLogoUrl();
            }
            if (storefrontConfig.getBannerUrl() != null) {
                bannerUrl = storefrontConfig.getBannerUrl();
            }
            if (storefrontConfig.getPrimaryColor() != null) {
                primaryColor = storefrontConfig.getPrimaryColor();
            }
            if (storefrontConfig.getSupportPhone() != null) {
                supportPhone = storefrontConfig.getSupportPhone();
            }
            if (storefrontConfig.getAddress() != null) {
                address = storefrontConfig.getAddress();
            }
        }

        return PublicTenantStorefrontResponse.builder()
                .subdomain(tenant.getSubdomain())
                .storeName(storeName)
                .logoUrl(logoUrl)
                .bannerUrl(bannerUrl)
                .primaryColor(primaryColor)
                .supportPhone(supportPhone)
                .address(address)
                .status(tenant.getStatus() != null ? tenant.getStatus().name() : "ACTIVE")
                .build();
    }
}
