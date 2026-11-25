package com.smartomni.common.tenant;

import com.smartomni.common.security.JwtTokenProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TenantFilterTest {

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    void forgedInternalHeadersCannotElevateRlsContext() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Tenant-Id", "2");
        request.addHeader("X-User-Role", "SUPER_ADMIN");
        new TenantFilter(mock(JwtTokenProvider.class)).doFilter(request, new MockHttpServletResponse(),
                (req, res) -> {
                    assertNull(TenantContext.getTenantId());
                    assertFalse(TenantContext.isSuperAdmin());
                });
        assertNull(TenantContext.getCurrentRole());
    }

    @Test
    void verifiedClaimsOverrideHeadersAndAreClearedOnFailure() {
        JwtTokenProvider jwt = mock(JwtTokenProvider.class);
        when(jwt.validateToken("valid")).thenReturn(true);
        when(jwt.getTenantId("valid")).thenReturn(1L);
        when(jwt.getRole("valid")).thenReturn("MANAGER");
        when(jwt.getUserId("valid")).thenReturn(10L);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer valid");
        request.addHeader("X-Tenant-Id", "2");
        assertThrows(IllegalStateException.class, () -> new TenantFilter(jwt)
                .doFilter(request, new MockHttpServletResponse(), (req, res) -> {
                    assertEquals(1L, TenantContext.getTenantId());
                    assertEquals("MANAGER", TenantContext.getCurrentRole());
                    throw new IllegalStateException("request failed");
                }));
        assertNull(TenantContext.getTenantId());
        assertNull(TenantContext.getCurrentRole());
    }

    @Test
    void backgroundScopeRestoresPreviousContextAfterFailure() {
        TenantContext.setTenantId(1L);
        TenantContext.setCurrentRole("MANAGER");
        assertThrows(IllegalStateException.class, () -> {
            try (var scope = TenantContext.openScope(null, "outbox_worker", null)) {
                assertNull(TenantContext.getTenantId());
                assertEquals("outbox_worker", TenantContext.getCurrentRole());
                throw new IllegalStateException("job failed");
            }
        });
        assertEquals(1L, TenantContext.getTenantId());
        assertEquals("MANAGER", TenantContext.getCurrentRole());
    }

    @Test
    void shouldNotFilterBypassesPublicAuthAndOnboarding() {
        TenantFilter filter = new TenantFilter(mock(JwtTokenProvider.class));

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
        protectedReq.setRequestURI("/api/v1/orders");
        assertFalse(filter.shouldNotFilter(protectedReq));
    }
}
