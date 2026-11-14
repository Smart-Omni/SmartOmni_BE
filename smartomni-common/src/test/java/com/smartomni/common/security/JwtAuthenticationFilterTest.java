package com.smartomni.common.security;

import com.smartomni.common.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private TokenBlacklistValidator tokenBlacklistValidator;

    @Mock
    private FilterChain filterChain;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Từ chối xác thực khi Token nằm trong Blacklist (sau khi logout)")
    void testDoFilter_TokenIsBlacklisted_RejectsAuthentication() throws Exception {
        String token = "blacklisted.jwt.token";
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(tokenBlacklistValidator.isBlacklisted(token)).thenReturn(true);

        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtTokenProvider, tokenBlacklistValidator, stringRedisTemplate);
        filter.doFilter(request, response, filterChain);

        verify(filterChain, times(1)).doFilter(request, response);
        verify(jwtTokenProvider, never()).validateToken(anyString());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertNull(TenantContext.getCurrentUserId());
        assertNull(TenantContext.getTenantId());
    }

    @Test
    @DisplayName("Xác thực thành công và nạp SecurityContext & TenantContext khi Token hợp lệ và không nằm trong Blacklist")
    void testDoFilter_ValidToken_NotBlacklisted_AuthenticatesSuccessfully() throws Exception {
        String token = "valid.jwt.token";
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(tokenBlacklistValidator.isBlacklisted(token)).thenReturn(false);
        when(jwtTokenProvider.validateToken(token)).thenReturn(true);
        when(jwtTokenProvider.getUserId(token)).thenReturn(10L);
        when(jwtTokenProvider.getRole(token)).thenReturn("ADMIN");
        when(jwtTokenProvider.getTenantId(token)).thenReturn(100L);

        when(jwtTokenProvider.getTokenVersion(token)).thenReturn(0L);
        when(stringRedisTemplate.opsForValue()).thenReturn(values);
        when(values.get(UserAccessState.key(10L))).thenReturn("ACTIVE:0");

        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtTokenProvider, tokenBlacklistValidator, stringRedisTemplate);
        filter.doFilter(request, response, filterChain);

        verify(filterChain, times(1)).doFilter(request, response);
        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals(10L, SecurityContextHolder.getContext().getAuthentication().getPrincipal());
        assertEquals(10L, TenantContext.getCurrentUserId());
        assertEquals("ADMIN", TenantContext.getCurrentRole());
        assertEquals(100L, TenantContext.getTenantId());
    }

    @Mock
    private org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate;

    @Mock
    private org.springframework.data.redis.core.ValueOperations<String, String> values;

    @Test
    @DisplayName("Từ chối request với HTTP 403 khi Tenant bị khóa trong Redis (auth:tenant_locked:<tenantId>)")
    void testDoFilter_TenantIsLocked_RejectsWithHttp403Forbidden() throws Exception {
        String token = "valid.tenant.jwt.token";
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(tokenBlacklistValidator.isBlacklisted(token)).thenReturn(false);
        when(jwtTokenProvider.validateToken(token)).thenReturn(true);
        when(jwtTokenProvider.getUserId(token)).thenReturn(10L);
        when(jwtTokenProvider.getRole(token)).thenReturn("ADMIN");
        when(jwtTokenProvider.getTenantId(token)).thenReturn(100L);
        when(jwtTokenProvider.getSubdomain(token)).thenReturn("locked-tenant");
        when(jwtTokenProvider.getTokenVersion(token)).thenReturn(0L);
        when(stringRedisTemplate.opsForValue()).thenReturn(values);
        when(values.get(UserAccessState.key(10L))).thenReturn("ACTIVE:0");
        when(stringRedisTemplate.hasKey("auth:tenant_locked:100")).thenReturn(true);

        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtTokenProvider, tokenBlacklistValidator, stringRedisTemplate);
        filter.doFilter(request, response, filterChain);

        // Không cho phép đi tiếp vào filter chain
        verify(filterChain, never()).doFilter(request, response);
        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("TENANT_LOCKED"));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    @DisplayName("Không xác thực khi Authorization Header không có tiền tố Bearer")
    void testDoFilter_NoBearerHeader_PassesThroughWithoutAuth() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Basic dXNlcjpwYXNz");
        MockHttpServletResponse response = new MockHttpServletResponse();

        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtTokenProvider, tokenBlacklistValidator, stringRedisTemplate);
        filter.doFilter(request, response, filterChain);

        verify(filterChain, times(1)).doFilter(request, response);
        verify(tokenBlacklistValidator, never()).isBlacklisted(anyString());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void disabledUserTokenIsRejectedBeforeAuthentication() throws Exception {
        String token = "disabled-token";
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/products");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(jwtTokenProvider.validateToken(token)).thenReturn(true);
        when(jwtTokenProvider.getUserId(token)).thenReturn(10L);
        when(jwtTokenProvider.getRole(token)).thenReturn("MANAGER");
        when(jwtTokenProvider.getTokenVersion(token)).thenReturn(0L);
        when(stringRedisTemplate.opsForValue()).thenReturn(values);
        when(values.get(UserAccessState.key(10L))).thenReturn("DISABLED:1");

        new JwtAuthenticationFilter(jwtTokenProvider, tokenBlacklistValidator, stringRedisTemplate)
                .doFilter(request, response, filterChain);

        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("TOKEN_REVOKED"));
        verifyNoInteractions(filterChain);
    }

    @Test
    @DisplayName("shouldNotFilter bỏ qua /api/v1/auth/** (trừ change-password), /api/v1/tenants/onboarding và /error")
    void testShouldNotFilter_PublicAuthOnboardingAndErrorPaths() throws Exception {
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtTokenProvider, tokenBlacklistValidator, stringRedisTemplate);

        MockHttpServletRequest loginRequest = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        loginRequest.addHeader("Authorization", "Bearer stale.locked.token");
        MockHttpServletRequest onboardingRequest = new MockHttpServletRequest("POST", "/api/v1/tenants/onboarding");
        MockHttpServletRequest errorRequest = new MockHttpServletRequest("GET", "/error");
        MockHttpServletRequest changePasswordRequest = new MockHttpServletRequest("POST", "/api/v1/auth/change-password");
        MockHttpServletRequest protectedRequest = new MockHttpServletRequest("GET", "/api/v1/products");

        assertTrue(filter.shouldNotFilter(loginRequest));
        assertTrue(filter.shouldNotFilter(onboardingRequest));
        assertTrue(filter.shouldNotFilter(errorRequest));
        assertFalse(filter.shouldNotFilter(changePasswordRequest));
        assertFalse(filter.shouldNotFilter(protectedRequest));

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(loginRequest, response, filterChain);
        verify(filterChain, times(1)).doFilter(loginRequest, response);
        verify(tokenBlacklistValidator, never()).isBlacklisted(anyString());
        verify(jwtTokenProvider, never()).validateToken(anyString());
    }

    @Test
    @DisplayName("STAFF chưa thuộc cửa hàng (tenantId=null) bị chặn ở API tenant nhưng được gọi /users/me và /users/me/invitations")
    void unassignedStaffBlockedFromTenantApisButAllowedOnSelfEndpoints() throws Exception {
        String token = "staff.unassigned.token";
        when(tokenBlacklistValidator.isBlacklisted(token)).thenReturn(false);
        when(jwtTokenProvider.validateToken(token)).thenReturn(true);
        when(jwtTokenProvider.getUserId(token)).thenReturn(20L);
        when(jwtTokenProvider.getRole(token)).thenReturn("STAFF");
        when(jwtTokenProvider.getTenantId(token)).thenReturn(null);
        when(jwtTokenProvider.getSubdomain(token)).thenReturn(null);
        when(jwtTokenProvider.getTokenVersion(token)).thenReturn(0L);
        when(stringRedisTemplate.opsForValue()).thenReturn(values);
        when(values.get(UserAccessState.key(20L))).thenReturn("ACTIVE:0");

        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtTokenProvider, tokenBlacklistValidator, stringRedisTemplate);

        MockHttpServletRequest tenantApiReq = new MockHttpServletRequest("GET", "/api/v1/products");
        tenantApiReq.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse tenantApiRes = new MockHttpServletResponse();
        filter.doFilter(tenantApiReq, tenantApiRes, filterChain);
        assertEquals(403, tenantApiRes.getStatus());
        assertTrue(tenantApiRes.getContentAsString().contains("STAFF_NO_TENANT"));
        verify(filterChain, never()).doFilter(tenantApiReq, tenantApiRes);

        MockHttpServletRequest selfInvitationsReq = new MockHttpServletRequest("GET", "/api/v1/users/me/invitations");
        selfInvitationsReq.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse selfInvitationsRes = new MockHttpServletResponse();
        filter.doFilter(selfInvitationsReq, selfInvitationsRes, filterChain);
        assertEquals(200, selfInvitationsRes.getStatus());
        verify(filterChain, times(1)).doFilter(selfInvitationsReq, selfInvitationsRes);
    }
}
