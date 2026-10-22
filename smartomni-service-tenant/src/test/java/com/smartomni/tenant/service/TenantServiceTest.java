package com.smartomni.tenant.service;

import com.smartomni.common.exception.BusinessException;
import com.smartomni.common.exception.ResourceNotFoundException;
import com.smartomni.common.tenant.TenantContext;
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
import com.smartomni.tenant.service.impl.TenantServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TenantService Unit Tests")
class TenantServiceTest {

    @Mock
    private TenantRepository tenantRepository;

    @Mock
    private SubscriptionPlanRepository subscriptionPlanRepository;

    @Mock
    private PlanFeatureFlagRepository featureFlagRepository;

    @Mock
    private org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate;

    @Mock
    private org.springframework.data.redis.core.ValueOperations<String, String> valueOperations;

    @org.mockito.Spy
    private com.smartomni.common.tenant.TenantProperties tenantProperties = new com.smartomni.common.tenant.TenantProperties();

    @InjectMocks
    private TenantServiceImpl tenantService;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("checkSubdomain: Subdomain hợp lệ và chưa tồn tại -> available = true")
    void testCheckSubdomain_ValidAndAvailable() {
        when(tenantRepository.existsBySubdomain("my-cool-store")).thenReturn(false);

        SubdomainCheckResponse response = tenantService.checkSubdomain("my-cool-store");

        assertTrue(response.isAvailable());
        assertEquals("my-cool-store", response.getSubdomain());
        assertEquals("Subdomain khả dụng", response.getMessage());
    }

    @Test
    @DisplayName("checkSubdomain: Subdomain đã tồn tại trong DB -> available = false")
    void testCheckSubdomain_AlreadyExists() {
        when(tenantRepository.existsBySubdomain("existing-shop")).thenReturn(true);

        SubdomainCheckResponse response = tenantService.checkSubdomain("existing-shop");

        assertFalse(response.isAvailable());
        assertEquals("Subdomain đã được sử dụng", response.getMessage());
    }

    @Test
    @DisplayName("checkSubdomain: Subdomain chứa từ khóa cấm (admin, api, auth, app, www) -> available = false")
    void testCheckSubdomain_ReservedKeywords() {
        for (String reserved : new String[]{"admin", "api", "auth", "app", "www", "my-admin", "app-store"}) {
            SubdomainCheckResponse response = tenantService.checkSubdomain(reserved);
            assertFalse(response.isAvailable(), "Subdomain chứa " + reserved + " phải bị từ chối");
            assertTrue(response.getMessage().contains("từ khóa cấm"));
        }
    }

    @Test
    @DisplayName("checkSubdomain: Subdomain sai định dạng (quá ngắn, ký tự hoa, ký tự đặc biệt) -> available = false")
    void testCheckSubdomain_InvalidFormat() {
        // Quá ngắn (< 3)
        SubdomainCheckResponse shortSub = tenantService.checkSubdomain("ab");
        assertFalse(shortSub.isAvailable());
        assertTrue(shortSub.getMessage().contains("từ 3 đến 30 ký tự"));

        // Chứa ký tự hoa hoặc đặc biệt
        SubdomainCheckResponse upperSub = tenantService.checkSubdomain("MyStore!");
        assertFalse(upperSub.isAvailable());
        assertTrue(upperSub.getMessage().contains("chỉ chứa ký tự chữ thường"));
    }

    @Test
    @DisplayName("onboardTenant: Thành công với gói FREE -> maxShops=1, maxSkus=100, AI=false")
    void testOnboardTenant_FreePlan() {
        TenantOnboardingRequest request = TenantOnboardingRequest.builder()
                .companyName("Shoe Store")
                .subdomain("shoe-store")
                .contactEmail("contact@shoestore.vn")
                .contactPhone("0987654321")
                .planType("FREE")
                .build();

        SubscriptionPlan freePlan = new SubscriptionPlan();
        freePlan.setId(10L);
        freePlan.setName("FREE");
        freePlan.setMaxMarketplaceConnections(1);
        freePlan.setMaxSkus(100);

        when(tenantRepository.existsBySubdomain("shoe-store")).thenReturn(false);
        when(subscriptionPlanRepository.findByName("FREE")).thenReturn(Optional.of(freePlan));
        when(subscriptionPlanRepository.findById(10L)).thenReturn(Optional.of(freePlan));
        when(featureFlagRepository.findByPlanIdAndFeatureKey(10L, "ai_features")).thenReturn(Optional.empty());
        when(featureFlagRepository.save(any(PlanFeatureFlag.class))).thenAnswer(i -> i.getArgument(0));

        when(tenantRepository.save(any(Tenant.class))).thenAnswer(i -> {
            Tenant t = i.getArgument(0);
            t.setId(1L);
            return t;
        });

        TenantProfileResponse response = tenantService.onboardTenant(request);

        assertNotNull(response);
        assertEquals("Shoe Store", response.getCompanyName());
        assertEquals("shoe-store", response.getSubdomain());
        assertEquals("FREE", response.getPlanType());
        assertEquals("ACTIVE", response.getStatus());
        assertEquals(1, response.getFeatures().getMaxShops());
        assertEquals(100, response.getFeatures().getMaxSkus());
        assertFalse(response.getFeatures().isAiFeaturesEnabled());
        assertNotNull(response.getStorefront());
        assertEquals("Shoe Store", response.getStorefront().getStoreName());
    }

    @Test
    @DisplayName("onboardTenant: Thành công với gói PRO -> maxShops không giới hạn, maxSkus không giới hạn, AI=true")
    void testOnboardTenant_ProPlan() {
        TenantOnboardingRequest request = TenantOnboardingRequest.builder()
                .companyName("Global Retailer")
                .subdomain("global-retail")
                .contactEmail("admin@globalretail.com")
                .contactPhone("0912345678")
                .planType("PRO")
                .build();

        SubscriptionPlan proPlan = new SubscriptionPlan();
        proPlan.setId(20L);
        proPlan.setName("PRO");
        proPlan.setMaxMarketplaceConnections(Integer.MAX_VALUE);
        proPlan.setMaxSkus(Integer.MAX_VALUE);

        when(tenantRepository.existsBySubdomain("global-retail")).thenReturn(false);
        when(subscriptionPlanRepository.findByName("PRO")).thenReturn(Optional.of(proPlan));
        when(subscriptionPlanRepository.findById(20L)).thenReturn(Optional.of(proPlan));
        when(featureFlagRepository.findByPlanIdAndFeatureKey(20L, "ai_features")).thenReturn(Optional.empty());
        when(featureFlagRepository.save(any(PlanFeatureFlag.class))).thenAnswer(i -> i.getArgument(0));

        when(tenantRepository.save(any(Tenant.class))).thenAnswer(i -> {
            Tenant t = i.getArgument(0);
            t.setId(2L);
            return t;
        });

        TenantProfileResponse response = tenantService.onboardTenant(request);

        assertNotNull(response);
        assertEquals("Global Retailer", response.getCompanyName());
        assertEquals("global-retail", response.getSubdomain());
        assertEquals("PRO", response.getPlanType());
        assertEquals(Integer.MAX_VALUE, response.getFeatures().getMaxShops());
        assertEquals(Integer.MAX_VALUE, response.getFeatures().getMaxSkus());
        assertTrue(response.getFeatures().isAiFeaturesEnabled());
    }

    @Test
    @DisplayName("onboardTenant: Ném BusinessException khi subdomain bị trùng")
    void testOnboardTenant_DuplicateSubdomain_ThrowsException() {
        TenantOnboardingRequest request = TenantOnboardingRequest.builder()
                .companyName("Duplicate Store")
                .subdomain("duplicate-sub")
                .contactEmail("dup@store.vn")
                .planType("FREE")
                .build();

        when(tenantRepository.existsBySubdomain("duplicate-sub")).thenReturn(true);

        BusinessException ex = assertThrows(BusinessException.class, () -> tenantService.onboardTenant(request));
        assertTrue(ex.getMessage().contains("đã được sử dụng"));
    }

    @Test
    @DisplayName("onboardTenant: Ném BusinessException khi subdomain chứa từ khóa cấm")
    void testOnboardTenant_ReservedSubdomain_ThrowsException() {
        TenantOnboardingRequest request = TenantOnboardingRequest.builder()
                .companyName("Admin Portal")
                .subdomain("admin")
                .contactEmail("admin@store.vn")
                .planType("FREE")
                .build();

        BusinessException ex = assertThrows(BusinessException.class, () -> tenantService.onboardTenant(request));
        assertTrue(ex.getMessage().contains("từ khóa cấm"));
    }

    @Test
    @DisplayName("getCurrentTenantProfile: Thành công khi TenantContext có tenantId")
    void testGetCurrentTenantProfile_Success() {
        TenantContext.openScope(100L, "MANAGER", null);

        Tenant tenant = new Tenant();
        tenant.setId(100L);
        tenant.setName("Context Store");
        tenant.setSubdomain("context-store");
        tenant.setStatus(Tenant.TenantStatus.ACTIVE);
        tenant.setPlanType("FREE");

        when(tenantRepository.findById(100L)).thenReturn(Optional.of(tenant));

        TenantProfileResponse response = tenantService.getCurrentTenantProfile();

        assertNotNull(response);
        assertEquals(100L, response.getId());
        assertEquals("context-store", response.getSubdomain());
    }

    @Test
    @DisplayName("getCurrentTenantProfile: Ném BusinessException khi TenantContext không có tenantId")
    void testGetCurrentTenantProfile_NoContext_ThrowsException() {
        TenantContext.clear();

        BusinessException ex = assertThrows(BusinessException.class, () -> tenantService.getCurrentTenantProfile());
        assertTrue(ex.getMessage().contains("Không tìm thấy thông tin tenant"));
    }

    @Test
    @DisplayName("updateStorefront: Cập nhật thành công cấu hình Storefront")
    void testUpdateStorefront_Success() {
        Tenant tenant = new Tenant();
        tenant.setId(5L);
        tenant.setName("Old Store Name");
        tenant.setSubdomain("my-store");
        tenant.setStatus(Tenant.TenantStatus.ACTIVE);

        when(tenantRepository.findById(5L)).thenReturn(Optional.of(tenant));
        when(tenantRepository.save(any(Tenant.class))).thenAnswer(i -> i.getArgument(0));

        StorefrontConfigRequest request = StorefrontConfigRequest.builder()
                .storeName("New Beautiful Store")
                .logoUrl("https://cdn.example.com/logo.png")
                .bannerUrl("https://cdn.example.com/banner.png")
                .primaryColor("#007BFF")
                .supportPhone("0988888888")
                .address("123 Nguyen Hue, Quan 1, TP.HCM")
                .build();

        TenantProfileResponse response = tenantService.updateStorefront(5L, request);

        assertNotNull(response);
        assertNotNull(response.getStorefront());
        assertEquals("New Beautiful Store", response.getStorefront().getStoreName());
        assertEquals("https://cdn.example.com/logo.png", response.getStorefront().getLogoUrl());
        assertEquals("https://cdn.example.com/banner.png", response.getStorefront().getBannerUrl());
        assertEquals("#007BFF", response.getStorefront().getPrimaryColor());
        assertEquals("0988888888", response.getStorefront().getSupportPhone());
        assertEquals("123 Nguyen Hue, Quan 1, TP.HCM", response.getStorefront().getAddress());
    }

    @Test
    @DisplayName("updateStorefront: Ném ResourceNotFoundException khi tenant không tồn tại")
    void testUpdateStorefront_NotFound_ThrowsException() {
        when(tenantRepository.findById(999L)).thenReturn(Optional.empty());

        StorefrontConfigRequest request = StorefrontConfigRequest.builder()
                .storeName("Store")
                .build();

        assertThrows(ResourceNotFoundException.class, () -> tenantService.updateStorefront(999L, request));
    }

    @Test
    @DisplayName("lockTenant: Cập nhật status LOCKED và đặt cờ auth:tenant_locked trên Redis")
    void testLockTenant_Success() {
        Tenant tenant = new Tenant();
        tenant.setId(10L);
        tenant.setStatus(Tenant.TenantStatus.ACTIVE);

        when(tenantRepository.findById(10L)).thenReturn(Optional.of(tenant));
        when(tenantRepository.save(any(Tenant.class))).thenAnswer(i -> i.getArgument(0));
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        tenantService.lockTenant(10L, "Vi phạm điều khoản", 1L);

        assertEquals(Tenant.TenantStatus.LOCKED, tenant.getStatus());
        assertEquals("Vi phạm điều khoản", tenant.getLockedReason());
        verify(valueOperations).set("auth:tenant_locked:10", "LOCKED");
    }

    @Test
    @DisplayName("unlockTenant: Cập nhật status ACTIVE và xóa cờ auth:tenant_locked khỏi Redis")
    void testUnlockTenant_Success() {
        Tenant tenant = new Tenant();
        tenant.setId(10L);
        tenant.setStatus(Tenant.TenantStatus.LOCKED);

        when(tenantRepository.findById(10L)).thenReturn(Optional.of(tenant));
        when(tenantRepository.save(any(Tenant.class))).thenAnswer(i -> i.getArgument(0));

        tenantService.unlockTenant(10L);

        assertEquals(Tenant.TenantStatus.ACTIVE, tenant.getStatus());
        assertNull(tenant.getLockedReason());
        verify(stringRedisTemplate).delete("auth:tenant_locked:10");
    }

    @Test
    @DisplayName("getPublicStorefrontProfile: Thành công khi subdomain tồn tại và active")
    void testGetPublicStorefrontProfile_Success() {
        Tenant tenant = new Tenant();
        tenant.setId(5L);
        tenant.setName("Awesome Shop");
        tenant.setSubdomain("awesome-shop");
        tenant.setStatus(Tenant.TenantStatus.ACTIVE);

        when(tenantRepository.findBySubdomain("awesome-shop")).thenReturn(Optional.of(tenant));

        com.smartomni.tenant.dto.PublicTenantStorefrontResponse response = tenantService.getPublicStorefrontProfile("awesome-shop");

        assertNotNull(response);
        assertEquals("awesome-shop", response.getSubdomain());
        assertEquals("Awesome Shop", response.getStoreName());
        assertEquals("ACTIVE", response.getStatus());
    }

    @Test
    @DisplayName("getPublicStorefrontProfile: Ném BusinessException khi tenant bị khóa")
    void testGetPublicStorefrontProfile_Locked_ThrowsException() {
        Tenant tenant = new Tenant();
        tenant.setId(5L);
        tenant.setName("Locked Shop");
        tenant.setSubdomain("locked-shop");
        tenant.setStatus(Tenant.TenantStatus.LOCKED);

        when(tenantRepository.findBySubdomain("locked-shop")).thenReturn(Optional.of(tenant));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> tenantService.getPublicStorefrontProfile("locked-shop"));
        assertTrue(ex.getMessage().contains("tạm ngưng"));
    }

    @Test
    @DisplayName("getPublicStorefrontProfile: Ném ResourceNotFoundException khi không tìm thấy subdomain")
    void testGetPublicStorefrontProfile_NotFound_ThrowsException() {
        when(tenantRepository.findBySubdomain("non-exist")).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> tenantService.getPublicStorefrontProfile("non-exist"));
    }
}
