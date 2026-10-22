package com.smartomni.tenant.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartomni.common.exception.GlobalExceptionHandler;
import com.smartomni.common.security.JwtTokenProvider;
import com.smartomni.common.tenant.TenantContext;
import com.smartomni.tenant.dto.PublicTenantStorefrontResponse;
import com.smartomni.tenant.dto.StorefrontConfigRequest;
import com.smartomni.tenant.dto.SubdomainCheckResponse;
import com.smartomni.tenant.dto.TenantOnboardingRequest;
import com.smartomni.tenant.dto.TenantProfileResponse;
import com.smartomni.tenant.service.TenantService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TenantController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
@DisplayName("TenantController WebMvc Tests")
class TenantControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private TenantService tenantService;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    @MockBean
    private StringRedisTemplate stringRedisTemplate;

    @MockBean
    private JpaMetamodelMappingContext jpaMetamodelMappingContext;

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("GET /api/v1/tenants/check-subdomain: Trả về available = true khi subdomain hợp lệ")
    void testCheckSubdomain_Available() throws Exception {
        SubdomainCheckResponse response = SubdomainCheckResponse.builder()
                .subdomain("brand-fashion")
                .available(true)
                .message("Subdomain khả dụng")
                .build();

        when(tenantService.checkSubdomain("brand-fashion")).thenReturn(response);

        mockMvc.perform(get("/api/v1/tenants/check-subdomain")
                        .param("subdomain", "brand-fashion"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.subdomain").value("brand-fashion"))
                .andExpect(jsonPath("$.data.available").value(true))
                .andExpect(jsonPath("$.data.message").value("Subdomain khả dụng"));
    }

    @Test
    @DisplayName("GET /api/v1/tenants/public/by-subdomain/{subdomain}: Trả về thông tin storefront công khai")
    void testGetPublicStorefront_Success() throws Exception {
        PublicTenantStorefrontResponse response = PublicTenantStorefrontResponse.builder()
                .subdomain("fashion-store")
                .storeName("Fashion Store")
                .logoUrl("https://example.com/logo.png")
                .primaryColor("#ff5722")
                .status("ACTIVE")
                .build();

        when(tenantService.getPublicStorefrontProfile("fashion-store")).thenReturn(response);

        mockMvc.perform(get("/api/v1/tenants/public/by-subdomain/fashion-store"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.subdomain").value("fashion-store"))
                .andExpect(jsonPath("$.data.storeName").value("Fashion Store"))
                .andExpect(jsonPath("$.data.logoUrl").value("https://example.com/logo.png"))
                .andExpect(jsonPath("$.data.primaryColor").value("#ff5722"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
    }

    @Test
    @DisplayName("GET /api/v1/tenants/check-subdomain: Trả về available = false khi subdomain chứa từ khóa cấm")
    void testCheckSubdomain_ReservedKeyword() throws Exception {
        SubdomainCheckResponse response = SubdomainCheckResponse.builder()
                .subdomain("admin")
                .available(false)
                .message("Subdomain chứa từ khóa cấm của hệ thống: admin")
                .build();

        when(tenantService.checkSubdomain("admin")).thenReturn(response);

        mockMvc.perform(get("/api/v1/tenants/check-subdomain")
                        .param("subdomain", "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.available").value(false));
    }

    @Test
    @DisplayName("POST /api/v1/tenants/onboarding: Thành công khi request hợp lệ")
    void testOnboarding_Success() throws Exception {
        TenantOnboardingRequest request = TenantOnboardingRequest.builder()
                .companyName("Smart Store")
                .subdomain("smart-store")
                .contactEmail("admin@smartstore.vn")
                .contactPhone("0909090909")
                .planType("FREE")
                .build();

        TenantProfileResponse response = TenantProfileResponse.builder()
                .id(1L)
                .companyName("Smart Store")
                .subdomain("smart-store")
                .status("ACTIVE")
                .planType("FREE")
                .features(TenantProfileResponse.PlanFeatureDto.builder()
                        .planName("FREE")
                        .maxShops(1)
                        .maxSkus(100)
                        .aiFeaturesEnabled(false)
                        .build())
                .build();

        when(tenantService.onboardTenant(any(TenantOnboardingRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/tenants/onboarding")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Khởi tạo Tenant thành công"))
                .andExpect(jsonPath("$.data.companyName").value("Smart Store"))
                .andExpect(jsonPath("$.data.subdomain").value("smart-store"))
                .andExpect(jsonPath("$.data.planType").value("FREE"))
                .andExpect(jsonPath("$.data.features.maxShops").value(1))
                .andExpect(jsonPath("$.data.features.maxSkus").value(100))
                .andExpect(jsonPath("$.data.features.aiFeaturesEnabled").value(false));
    }

    @Test
    @DisplayName("POST /api/v1/tenants/onboarding: Thất bại khi subdomain vi phạm regex (quá ngắn hoặc ký tự hoa)")
    void testOnboarding_ValidationFailure_InvalidSubdomain() throws Exception {
        TenantOnboardingRequest request = TenantOnboardingRequest.builder()
                .companyName("Store")
                .subdomain("AB") // Ngắn hơn 3 ký tự và chữ hoa
                .contactEmail("valid@store.com")
                .build();

        mockMvc.perform(post("/api/v1/tenants/onboarding")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/v1/tenants/me: Trả về hồ sơ chi tiết khi TenantContext hợp lệ")
    void testGetTenantProfile_Success() throws Exception {
        TenantProfileResponse response = TenantProfileResponse.builder()
                .id(10L)
                .companyName("My Retail")
                .subdomain("my-retail")
                .status("ACTIVE")
                .planType("PRO")
                .features(TenantProfileResponse.PlanFeatureDto.builder()
                        .planName("PRO")
                        .maxShops(Integer.MAX_VALUE)
                        .maxSkus(Integer.MAX_VALUE)
                        .aiFeaturesEnabled(true)
                        .build())
                .build();

        when(tenantService.getCurrentTenantProfile()).thenReturn(response);

        mockMvc.perform(get("/api/v1/tenants/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.companyName").value("My Retail"))
                .andExpect(jsonPath("$.data.features.aiFeaturesEnabled").value(true));
    }

    @Test
    @DisplayName("PUT /api/v1/tenants/storefront: Cập nhật thành công cấu hình Storefront")
    void testUpdateStorefront_Success() throws Exception {
        TenantContext.openScope(10L, "MANAGER", null);

        StorefrontConfigRequest request = StorefrontConfigRequest.builder()
                .storeName("Fashion Haven")
                .logoUrl("https://example.com/logo.jpg")
                .bannerUrl("https://example.com/banner.jpg")
                .primaryColor("#FF5722")
                .supportPhone("0912345678")
                .address("456 Le Loi, Da Nang")
                .build();

        TenantProfileResponse response = TenantProfileResponse.builder()
                .id(10L)
                .companyName("Fashion Haven")
                .subdomain("fashion-haven")
                .storefront(TenantProfileResponse.StorefrontConfigDto.builder()
                        .storeName("Fashion Haven")
                        .logoUrl("https://example.com/logo.jpg")
                        .bannerUrl("https://example.com/banner.jpg")
                        .primaryColor("#FF5722")
                        .supportPhone("0912345678")
                        .address("456 Le Loi, Da Nang")
                        .build())
                .build();

        when(tenantService.updateStorefront(eq(10L), any(StorefrontConfigRequest.class))).thenReturn(response);

        mockMvc.perform(put("/api/v1/tenants/storefront")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Cập nhật storefront thành công"))
                .andExpect(jsonPath("$.data.storefront.storeName").value("Fashion Haven"))
                .andExpect(jsonPath("$.data.storefront.primaryColor").value("#FF5722"));
    }

    @Test
    @DisplayName("PUT /api/v1/tenants/storefront: Báo lỗi 400 khi storeName trống")
    void testUpdateStorefront_ValidationFailure() throws Exception {
        TenantContext.openScope(10L, "MANAGER", null);

        StorefrontConfigRequest request = StorefrontConfigRequest.builder()
                .storeName("") // Trống
                .build();

        mockMvc.perform(put("/api/v1/tenants/storefront")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PUT /api/v1/tenants/storefront: Ném lỗi khi TenantContext không có tenantId")
    void testUpdateStorefront_NoTenantContext() throws Exception {
        TenantContext.clear();

        StorefrontConfigRequest request = StorefrontConfigRequest.builder()
                .storeName("My Store")
                .build();

        mockMvc.perform(put("/api/v1/tenants/storefront")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Không tìm thấy thông tin tenant trong context"));
    }

    @Test
    @DisplayName("GET /api/tenants/{tenantId}/status: Tương thích ngược endpoint cũ")
    void testGetStatus_BackwardCompatibility() throws Exception {
        when(tenantService.isTenantActive(1L)).thenReturn(true);

        mockMvc.perform(get("/api/tenants/1/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value(true));
    }
}
