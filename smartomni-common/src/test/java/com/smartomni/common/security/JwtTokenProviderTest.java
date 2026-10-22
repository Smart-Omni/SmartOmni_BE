package com.smartomni.common.security;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("JwtTokenProvider Unit Tests")
class JwtTokenProviderTest {

    private JwtTokenProvider jwtTokenProvider;
    private final String secret = "1234567890123456789012345678901234567890123456789012345678901234";

    @BeforeEach
    void setUp() {
        jwtTokenProvider = new JwtTokenProvider();
        ReflectionTestUtils.setField(jwtTokenProvider, "jwtSecret", secret);
        ReflectionTestUtils.setField(jwtTokenProvider, "expirationMs", 3600000L);
    }

    @Test
    @DisplayName("JWT sinh ra phải chứa đủ 4 claims chuẩn: user_id, tenant_id, subdomain, role")
    void testGenerateToken_ContainsAllFourClaims() {
        Long userId = 42L;
        String role = "TENANT_ADMIN";
        Long tenantId = 100L;
        String subdomain = "techcorp";

        String token = jwtTokenProvider.generateToken(userId, role, tenantId, subdomain);
        assertNotNull(token);
        assertTrue(jwtTokenProvider.validateToken(token));

        Claims claims = jwtTokenProvider.parseClaims(token);

        assertEquals("42", claims.getSubject());
        assertEquals(42, ((Number) claims.get("user_id")).longValue());
        assertEquals("TENANT_ADMIN", claims.get("role"));
        assertEquals(100, ((Number) claims.get("tenant_id")).longValue());
        assertEquals("techcorp", claims.get("subdomain"));

        // Kiểm tra qua các getter methods
        assertEquals(42L, jwtTokenProvider.getUserId(token));
        assertEquals("TENANT_ADMIN", jwtTokenProvider.getRole(token));
        assertEquals(100L, jwtTokenProvider.getTenantId(token));
        assertEquals("techcorp", jwtTokenProvider.getSubdomain(token));
    }

    @Test
    @DisplayName("JWT Super Admin không có tenantId và subdomain")
    void testGenerateToken_SuperAdmin_NullTenantAndSubdomain() {
        Long userId = 1L;
        String role = "SUPER_ADMIN";

        String token = jwtTokenProvider.generateToken(userId, role, null, null);
        assertNotNull(token);
        assertTrue(jwtTokenProvider.validateToken(token));

        assertEquals(1L, jwtTokenProvider.getUserId(token));
        assertEquals("SUPER_ADMIN", jwtTokenProvider.getRole(token));
        assertNull(jwtTokenProvider.getTenantId(token));
        assertNull(jwtTokenProvider.getSubdomain(token));
    }
}
