package com.smartomni.tenant.service.impl;

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
import com.smartomni.tenant.entity.TenantUser;
import com.smartomni.tenant.repository.PlanFeatureFlagRepository;
import com.smartomni.tenant.repository.SubscriptionPlanRepository;
import com.smartomni.tenant.repository.TenantRepository;
import com.smartomni.tenant.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TenantServiceImpl Unit Tests")
public class TenantServiceImplTest {

    @Mock
    private TenantRepository tenantRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private SubscriptionPlanRepository subscriptionPlanRepository;

    @Mock
    private PlanFeatureFlagRepository featureFlagRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

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
    @DisplayName("onboardTenant: Thành công với gói FREE -> maxShops=1, maxSkus=100, AI=false, tạo User Admin")
    void testOnboardTenant_FreePlan() {
        TenantOnboardingRequest request = TenantOnboardingRequest.builder()
                .companyName("Shoe Store")
                .subdomain("shoe-store")
                .adminEmail("admin@shoestore.vn")
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
        when(userRepository.findByEmail("admin@shoestore.vn")).thenReturn(Optional.empty());
        when(passwordEncoder.encode(anyString())).thenReturn("hashed_password");
        when(subscriptionPlanRepository.findByName("FREE")).thenReturn(Optional.of(freePlan));
        when(subscriptionPlanRepository.findById(10L)).thenReturn(Optional.of(freePlan));
        when(featureFlagRepository.findByPlanIdAndFeatureKey(10L, "ai_features")).thenReturn(Optional.empty());
        when(featureFlagRepository.save(any(PlanFeatureFlag.class))).thenAnswer(i -> i.getArgument(0));

        when(tenantRepository.save(any(Tenant.class))).thenAnswer(i -> {
            Tenant t = i.getArgument(0);
            t.setId(1L);
            return t;
        });

        when(userRepository.save(any(TenantUser.class))).thenAnswer(i -> {
            TenantUser u = i.getArgument(0);
            u.setId(99L);
            return u;
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

        // Kiểm tra thông tin Admin user vừa tạo
        assertEquals(99L, response.getAdminUserId());
        assertEquals("admin@shoestore.vn", response.getAdminEmail());
        assertNotNull(response.getAdminFullName());

        verify(userRepository, times(1)).save(any(TenantUser.class));
        verify(passwordEncoder, times(1)).encode(anyString());
    }

    @Test
    @DisplayName("onboardTenant: Thành công với gói PRO -> maxShops không giới hạn, maxSkus không giới hạn, AI=true, tạo User Admin")
    void testOnboardTenant_ProPlan() {
        TenantOnboardingRequest request = TenantOnboardingRequest.builder()
                .companyName("Global Retailer")
                .subdomain("global-retail")
                .adminEmail("admin@globalretail.com")
                .contactPhone("0912345678")
                .planType("PRO")
                .build();

        SubscriptionPlan proPlan = new SubscriptionPlan();
        proPlan.setId(20L);
        proPlan.setName("PRO");
        proPlan.setMaxMarketplaceConnections(Integer.MAX_VALUE);
        proPlan.setMaxSkus(Integer.MAX_VALUE);

        when(tenantRepository.existsBySubdomain("global-retail")).thenReturn(false);
        when(userRepository.findByEmail("admin@globalretail.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode(anyString())).thenReturn("hashed_pro_password");
        when(subscriptionPlanRepository.findByName("PRO")).thenReturn(Optional.of(proPlan));
        when(subscriptionPlanRepository.findById(20L)).thenReturn(Optional.of(proPlan));
        when(featureFlagRepository.findByPlanIdAndFeatureKey(20L, "ai_features")).thenReturn(Optional.empty());
        when(featureFlagRepository.save(any(PlanFeatureFlag.class))).thenAnswer(i -> i.getArgument(0));

        when(tenantRepository.save(any(Tenant.class))).thenAnswer(i -> {
            Tenant t = i.getArgument(0);
            t.setId(2L);
            return t;
        });

        when(userRepository.save(any(TenantUser.class))).thenAnswer(i -> {
            TenantUser u = i.getArgument(0);
            u.setId(101L);
            return u;
        });

        TenantProfileResponse response = tenantService.onboardTenant(request);

        assertNotNull(response);
        assertEquals("Global Retailer", response.getCompanyName());
        assertEquals("global-retail", response.getSubdomain());
        assertEquals("PRO", response.getPlanType());
        assertEquals(Integer.MAX_VALUE, response.getFeatures().getMaxShops());
        assertEquals(Integer.MAX_VALUE, response.getFeatures().getMaxSkus());
        assertTrue(response.getFeatures().isAiFeaturesEnabled());

        assertEquals(101L, response.getAdminUserId());
        assertEquals("admin@globalretail.com", response.getAdminEmail());
    }

    @Test
    @DisplayName("onboardTenant: Ném BusinessException 409 Conflict khi adminEmail đã tồn tại trong hệ thống")
    void testOnboardTenant_DuplicateAdminEmail_ThrowsConflict() {
        TenantOnboardingRequest request = TenantOnboardingRequest.builder()
                .companyName("Existing User Shop")
                .subdomain("exist-user-shop")
                .adminEmail("admin@shoestore.vn")
                .planType("FREE")
                .build();

        when(tenantRepository.existsBySubdomain("exist-user-shop")).thenReturn(false);
        when(userRepository.findByEmail("admin@shoestore.vn")).thenReturn(Optional.of(new TenantUser()));

        BusinessException ex = assertThrows(BusinessException.class, () -> tenantService.onboardTenant(request));
        assertEquals(HttpStatus.CONFLICT, ex.getHttpStatus());
        assertEquals("EMAIL_ALREADY_EXISTS", ex.getErrorCode());
        assertTrue(ex.getMessage().contains("đã tồn tại"));

        verify(tenantRepository, never()).save(any());
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("onboardTenant: Ném BusinessException 400 khi adminEmail để trống")
    void testOnboardTenant_BlankAdminEmail_ThrowsBadRequest() {
        TenantOnboardingRequest request = TenantOnboardingRequest.builder()
                .companyName("Empty Email Shop")
                .subdomain("empty-user-shop")
                .adminEmail("")
                .planType("FREE")
                .build();

        when(tenantRepository.existsBySubdomain("empty-user-shop")).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class, () -> tenantService.onboardTenant(request));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getHttpStatus());
        assertTrue(ex.getMessage().contains("không được để trống"));

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("onboardTenant: Tạo Admin User với custom adminFullName và adminPassword")
    void testOnboardTenant_WithCustomPasswordAndFullName() {
        TenantOnboardingRequest request = TenantOnboardingRequest.builder()
                .companyName("Custom Owner Shop")
                .subdomain("custom-owner-shop")
                .adminEmail("owner@customshop.vn")
                .adminFullName("Nguyen Van Owner")
                .adminPassword("SecretP@ss123")
                .planType("FREE")
                .build();

        SubscriptionPlan freePlan = new SubscriptionPlan();
        freePlan.setId(10L);
        freePlan.setName("FREE");

        when(tenantRepository.existsBySubdomain("custom-owner-shop")).thenReturn(false);
        when(userRepository.findByEmail("owner@customshop.vn")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("SecretP@ss123")).thenReturn("encoded_SecretP@ss123");
        when(subscriptionPlanRepository.findByName("FREE")).thenReturn(Optional.of(freePlan));
        when(subscriptionPlanRepository.findById(10L)).thenReturn(Optional.of(freePlan));
        when(featureFlagRepository.findByPlanIdAndFeatureKey(10L, "ai_features")).thenReturn(Optional.empty());
        when(featureFlagRepository.save(any(PlanFeatureFlag.class))).thenAnswer(i -> i.getArgument(0));

        when(tenantRepository.save(any(Tenant.class))).thenAnswer(i -> {
            Tenant t = i.getArgument(0);
            t.setId(5L);
            return t;
        });

        when(userRepository.save(any(TenantUser.class))).thenAnswer(i -> {
            TenantUser u = i.getArgument(0);
            u.setId(205L);
            return u;
        });

        TenantProfileResponse response = tenantService.onboardTenant(request);

        assertNotNull(response);
        assertEquals(205L, response.getAdminUserId());
        assertEquals("owner@customshop.vn", response.getAdminEmail());
        assertEquals("Nguyen Van Owner", response.getAdminFullName());

        verify(passwordEncoder, times(1)).encode("SecretP@ss123");
    }

    @Test
    @DisplayName("onboardTenant: Rollback transaction khi lưu User thất bại")
    void testOnboardTenant_RollbackOnError_WhenUserSaveFails() {
        TenantOnboardingRequest request = TenantOnboardingRequest.builder()
                .companyName("Fail User Shop")
                .subdomain("fail-user-shop")
                .adminEmail("fail@usershop.vn")
                .planType("FREE")
                .build();

        SubscriptionPlan freePlan = new SubscriptionPlan();
        freePlan.setId(10L);
        freePlan.setName("FREE");

        when(tenantRepository.existsBySubdomain("fail-user-shop")).thenReturn(false);
        when(userRepository.findByEmail("fail@usershop.vn")).thenReturn(Optional.empty());
        when(passwordEncoder.encode(anyString())).thenReturn("hashed_fail");
        when(subscriptionPlanRepository.findByName("FREE")).thenReturn(Optional.of(freePlan));
        when(featureFlagRepository.findByPlanIdAndFeatureKey(10L, "ai_features")).thenReturn(Optional.empty());
        when(featureFlagRepository.save(any(PlanFeatureFlag.class))).thenAnswer(i -> i.getArgument(0));

        when(tenantRepository.save(any(Tenant.class))).thenAnswer(i -> {
            Tenant t = i.getArgument(0);
            t.setId(88L);
            return t;
        });

        when(userRepository.save(any(TenantUser.class))).thenThrow(new RuntimeException("Database error saving admin user"));

        RuntimeException ex = assertThrows(RuntimeException.class, () -> tenantService.onboardTenant(request));
        assertTrue(ex.getMessage().contains("Database error saving admin user"));
    }

    @Test
    @DisplayName("onboardTenant: Ném BusinessException khi subdomain bị trùng")
    void testOnboardTenant_DuplicateSubdomain_ThrowsException() {
        TenantOnboardingRequest request = TenantOnboardingRequest.builder()
                .companyName("Duplicate Store")
                .subdomain("duplicate-sub")
                .adminEmail("dup@store.vn")
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
                .adminEmail("admin@store.vn")
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
        assertThrows(BusinessException.class, () -> tenantService.getCurrentTenantProfile());
    }

    @Test
    @DisplayName("getTenantProfile: Ném ResourceNotFoundException khi không tìm thấy Tenant")
    void testGetTenantProfile_NotFound_ThrowsException() {
        when(tenantRepository.findById(999L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> tenantService.getTenantProfile(999L));
    }

    @Test
    @DisplayName("updateStorefront: Cập nhật thành công cấu hình cửa hàng")
    void testUpdateStorefront_Success() {
        Tenant tenant = new Tenant();
        tenant.setId(10L);
        tenant.setName("My Shop");
        tenant.setSubdomain("my-shop");
        tenant.setStatus(Tenant.TenantStatus.ACTIVE);
        tenant.setPlanType("FREE");

        when(tenantRepository.findById(10L)).thenReturn(Optional.of(tenant));
        when(tenantRepository.save(any(Tenant.class))).thenReturn(tenant);

        StorefrontConfigRequest config = StorefrontConfigRequest.builder()
                .storeName("Custom Shop Name")
                .logoUrl("https://example.com/new-logo.png")
                .bannerUrl("https://example.com/new-banner.png")
                .primaryColor("#00ff00")
                .supportPhone("0123456789")
                .address("123 Ha Noi")
                .build();

        TenantProfileResponse response = tenantService.updateStorefront(10L, config);

        assertNotNull(response);
        assertNotNull(response.getStorefront());
        assertEquals("Custom Shop Name", response.getStorefront().getStoreName());
        assertEquals("#00ff00", response.getStorefront().getPrimaryColor());
        assertEquals("123 Ha Noi", response.getStorefront().getAddress());
    }

    @Test
    @DisplayName("searchTenants: Tìm kiếm tenant theo từ khóa thành công")
    void testSearchTenants_Success() {
        Tenant tenant = new Tenant();
        tenant.setId(1L);
        tenant.setName("Search Target Store");
        tenant.setSubdomain("search-target");

        Page<Tenant> page = new PageImpl<>(List.of(tenant));
        when(tenantRepository.findByNameContainingIgnoreCaseOrSubdomainContainingIgnoreCase("target", "target", PageRequest.of(0, 10)))
                .thenReturn(page);

        Page<Tenant> result = tenantService.searchTenants("target", PageRequest.of(0, 10));

        assertEquals(1, result.getTotalElements());
        assertEquals("Search Target Store", result.getContent().get(0).getName());
    }

    @Test
    @DisplayName("lockTenant: Khóa tenant thành công và ghi cờ Redis")
    void testLockTenant_Success() {
        Tenant tenant = new Tenant();
        tenant.setId(5L);
        tenant.setStatus(Tenant.TenantStatus.ACTIVE);

        when(tenantRepository.findById(5L)).thenReturn(Optional.of(tenant));
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        tenantService.lockTenant(5L, "Vi phạm chính sách thanh toán", 1L);

        assertEquals(Tenant.TenantStatus.LOCKED, tenant.getStatus());
        assertEquals("Vi phạm chính sách thanh toán", tenant.getLockedReason());
        verify(tenantRepository, times(1)).save(tenant);
        verify(valueOperations, times(1)).set(eq("auth:tenant_locked:5"), eq("LOCKED"));
    }

    @Test
    @DisplayName("unlockTenant: Mở khóa tenant thành công và xóa cờ Redis")
    void testUnlockTenant_Success() {
        Tenant tenant = new Tenant();
        tenant.setId(5L);
        tenant.setStatus(Tenant.TenantStatus.LOCKED);
        tenant.setLockedReason("Tạm khóa để đối soát");

        when(tenantRepository.findById(5L)).thenReturn(Optional.of(tenant));

        tenantService.unlockTenant(5L);

        assertEquals(Tenant.TenantStatus.ACTIVE, tenant.getStatus());
        assertNull(tenant.getLockedReason());
        verify(tenantRepository, times(1)).save(tenant);
        verify(stringRedisTemplate, times(1)).delete(eq("auth:tenant_locked:5"));
    }

    @Test
    @DisplayName("isTenantActive: Trả về true khi ACTIVE, false khi LOCKED hoặc không tồn tại")
    void testIsTenantActive() {
        Tenant activeTenant = new Tenant();
        activeTenant.setStatus(Tenant.TenantStatus.ACTIVE);
        when(tenantRepository.findById(1L)).thenReturn(Optional.of(activeTenant));

        Tenant lockedTenant = new Tenant();
        lockedTenant.setStatus(Tenant.TenantStatus.LOCKED);
        when(tenantRepository.findById(2L)).thenReturn(Optional.of(lockedTenant));

        when(tenantRepository.findById(3L)).thenReturn(Optional.empty());

        assertTrue(tenantService.isTenantActive(1L));
        assertFalse(tenantService.isTenantActive(2L));
        assertFalse(tenantService.isTenantActive(3L));
    }

    @Test
    @DisplayName("getPublicStorefrontProfile: Thành công với subdomain hợp lệ và tenant ACTIVE")
    void testGetPublicStorefrontProfile_Success() {
        Tenant tenant = new Tenant();
        tenant.setId(10L);
        tenant.setSubdomain("beauty-shop");
        tenant.setName("Beauty Shop");
        tenant.setStatus(Tenant.TenantStatus.ACTIVE);
        tenant.setLogoUrl("https://example.com/logo.png");
        tenant.setPrimaryColor("#e91e63");

        when(tenantRepository.findBySubdomain("beauty-shop")).thenReturn(Optional.of(tenant));

        var response = tenantService.getPublicStorefrontProfile("beauty-shop");

        assertNotNull(response);
        assertEquals("beauty-shop", response.getSubdomain());
        assertEquals("Beauty Shop", response.getStoreName());
        assertEquals("#e91e63", response.getPrimaryColor());
        assertEquals("ACTIVE", response.getStatus());
    }

    @Test
    @DisplayName("getPublicStorefrontProfile: Ném BusinessException khi tenant bị LOCKED")
    void testGetPublicStorefrontProfile_Locked_ThrowsException() {
        Tenant lockedTenant = new Tenant();
        lockedTenant.setId(10L);
        lockedTenant.setSubdomain("banned-shop");
        lockedTenant.setStatus(Tenant.TenantStatus.LOCKED);

        when(tenantRepository.findBySubdomain("banned-shop")).thenReturn(Optional.of(lockedTenant));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                tenantService.getPublicStorefrontProfile("banned-shop"));
        assertTrue(ex.getMessage().contains("tạm ngưng hoạt động"));
    }

    @Test
    @DisplayName("getPublicStorefrontProfile: Ném ResourceNotFoundException khi không tìm thấy subdomain")
    void testGetPublicStorefrontProfile_NotFound_ThrowsException() {
        when(tenantRepository.findBySubdomain("unknown-shop")).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () ->
                tenantService.getPublicStorefrontProfile("unknown-shop"));
    }
}
