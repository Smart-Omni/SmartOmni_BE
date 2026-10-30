package com.smartomni.common.tenant;

import com.smartomni.common.constant.AppConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SubdomainContextFilter Tests")
class SubdomainContextFilterTest {

    private TenantProperties tenantProperties;
    private SubdomainContextFilter filter;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        tenantProperties = new TenantProperties();
        tenantProperties.setBaseDomain("smartomni.vn");
        filter = new SubdomainContextFilter(tenantProperties);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("Trích xuất subdomain thành công từ header X-Tenant-Subdomain")
    void testExtractFromCustomHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(AppConstants.HEADER_TENANT_SUBDOMAIN, "apple-store");

        AtomicReference<String> capturedSubdomain = new AtomicReference<>();
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            capturedSubdomain.set(TenantContext.getCurrentSubdomain());
        });

        assertEquals("apple-store", capturedSubdomain.get());
        assertNull(TenantContext.getCurrentSubdomain(), "Context phải được clear sau khi kết thúc request");
    }

    @Test
    @DisplayName("Trích xuất subdomain thành công từ header Host có baseDomain")
    void testExtractFromHostWithBaseDomain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Host", "fashion-brand.smartomni.vn:8080");

        AtomicReference<String> capturedSubdomain = new AtomicReference<>();
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            capturedSubdomain.set(TenantContext.getCurrentSubdomain());
        });

        assertEquals("fashion-brand", capturedSubdomain.get());
        assertNull(TenantContext.getCurrentSubdomain());
    }

    @Test
    @DisplayName("Trích xuất subdomain thành công từ header X-Forwarded-Host")
    void testExtractFromXForwardedHost() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-Host", "super-mart.smartomni.vn");

        AtomicReference<String> capturedSubdomain = new AtomicReference<>();
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            capturedSubdomain.set(TenantContext.getCurrentSubdomain());
        });

        assertEquals("super-mart", capturedSubdomain.get());
    }

    @Test
    @DisplayName("Trích xuất subdomain từ localhost trong môi trường local development")
    void testExtractFromLocalhost() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Host", "cool-shop.localhost");

        AtomicReference<String> capturedSubdomain = new AtomicReference<>();
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            capturedSubdomain.set(TenantContext.getCurrentSubdomain());
        });

        assertEquals("cool-shop", capturedSubdomain.get());
    }

    @Test
    @DisplayName("Không gán subdomain nếu thuộc danh sách reserved (ví dụ: admin, api, auth, www)")
    void testReservedSubdomainIsIgnored() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Host", "api.smartomni.vn");

        AtomicReference<String> capturedSubdomain = new AtomicReference<>();
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            capturedSubdomain.set(TenantContext.getCurrentSubdomain());
        });

        assertNull(capturedSubdomain.get(), "Subdomain 'api' là từ khóa cấm nên không được gán");
    }

    @Test
    @DisplayName("Không gán subdomain nếu định dạng không hợp lệ")
    void testInvalidSubdomainFormatIsIgnored() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(AppConstants.HEADER_TENANT_SUBDOMAIN, "ab"); // Ngắn hơn 3 ký tự

        AtomicReference<String> capturedSubdomain = new AtomicReference<>();
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            capturedSubdomain.set(TenantContext.getCurrentSubdomain());
        });

        assertNull(capturedSubdomain.get());
    }

    @Test
    @DisplayName("Không ghi đè subdomain đã có sẵn từ JWT trong TenantContext")
    void testPreserveExistingSubdomainFromJwt() throws Exception {
        TenantContext.setCurrentSubdomain("jwt-store");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(AppConstants.HEADER_TENANT_SUBDOMAIN, "header-store");

        AtomicReference<String> capturedSubdomain = new AtomicReference<>();
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            capturedSubdomain.set(TenantContext.getCurrentSubdomain());
        });

        assertEquals("jwt-store", capturedSubdomain.get());
        assertEquals("jwt-store", TenantContext.getCurrentSubdomain(), "Subdomain ban đầu không bị xóa bởi filter");
    }

    @Test
    @DisplayName("shouldNotFilter trả về true cho /api/v1/auth/** và /api/v1/tenants/onboarding")
    void testShouldNotFilterForAuthAndOnboarding() {
        MockHttpServletRequest authReq = new MockHttpServletRequest();
        authReq.setRequestURI("/api/v1/auth/register");
        assertTrue(filter.shouldNotFilter(authReq));

        MockHttpServletRequest loginReq = new MockHttpServletRequest();
        loginReq.setRequestURI("/api/v1/auth/login");
        assertTrue(filter.shouldNotFilter(loginReq));

        MockHttpServletRequest onboardingReq = new MockHttpServletRequest();
        onboardingReq.setRequestURI("/api/v1/tenants/onboarding");
        assertTrue(filter.shouldNotFilter(onboardingReq));

        MockHttpServletRequest protectedReq = new MockHttpServletRequest();
        protectedReq.setRequestURI("/api/v1/products");
        assertFalse(filter.shouldNotFilter(protectedReq));
    }
}
