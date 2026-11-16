package com.smartomni.common.security;

import com.smartomni.common.constant.AppConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = DomainServiceBlacklistIntegrationTest.TestProtectedDomainController.class)
@ContextConfiguration(classes = DomainServiceBlacklistIntegrationTest.TestConfig.class)
class DomainServiceBlacklistIntegrationTest {

    @RestController
    static class TestProtectedDomainController {
        @GetMapping("/api/v1/catalog/products")
        public String getProducts() {
            return "products-data";
        }

        @org.springframework.web.bind.annotation.PostMapping("/api/v1/auth/login")
        public String login() {
            return "login-ok";
        }

        @org.springframework.web.bind.annotation.PostMapping("/api/v1/tenants/onboarding")
        public String onboarding() {
            return "onboarding-ok";
        }

        @GetMapping("/error")
        public String error() {
            return "error-ok";
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(ServiceSecurityAutoConfiguration.class)
    static class TestConfig {
        @Bean
        public TestProtectedDomainController testProtectedDomainController() {
            return new TestProtectedDomainController();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    @MockBean
    private StringRedisTemplate stringRedisTemplate;

    @org.springframework.boot.test.mock.mockito.MockBean
    private org.springframework.data.redis.core.ValueOperations<String, String> values;

    @Test
    void missingTokenUsesJsonEntryPoint() throws Exception {
        mockMvc.perform(get("/api/v1/catalog/products"))
                .andExpect(status().isUnauthorized())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.success").value(false))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void malformedTokenIsUnauthorized() throws Exception {
        when(jwtTokenProvider.parseClaims("malformed"))
                .thenThrow(new io.jsonwebtoken.MalformedJwtException("malformed"));
        mockMvc.perform(get("/api/v1/catalog/products").header("Authorization", "Bearer malformed"))
                .andExpect(status().isUnauthorized())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("Domain service từ chối request khi token nằm trong Redis Blacklist (HTTP 401)")
    void testDomainServiceRejectsBlacklistedToken() throws Exception {
        String token = "blacklisted-token";
        when(stringRedisTemplate.hasKey(AppConstants.REDIS_PREFIX_BLACKLIST + token)).thenReturn(true);
        when(jwtTokenProvider.parseClaims(token)).thenReturn(org.mockito.Mockito.mock(io.jsonwebtoken.Claims.class));

        mockMvc.perform(get("/api/v1/catalog/products")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("TOKEN_REVOKED"));
    }

    @Test
    @DisplayName("Domain service chấp nhận request khi token hợp lệ và không nằm trong Redis Blacklist (HTTP 200)")
    void testDomainServiceAcceptsValidNonBlacklistedToken() throws Exception {
        String token = "valid-domain-token";
        when(stringRedisTemplate.hasKey(AppConstants.REDIS_PREFIX_BLACKLIST + token)).thenReturn(false);
        when(jwtTokenProvider.parseClaims(token)).thenReturn(org.mockito.Mockito.mock(io.jsonwebtoken.Claims.class));
        when(jwtTokenProvider.getUserId(token)).thenReturn(10L);
        when(jwtTokenProvider.getRole(token)).thenReturn("ADMIN");
        when(jwtTokenProvider.getTenantId(token)).thenReturn(100L);
        when(jwtTokenProvider.getTokenVersion(token)).thenReturn(0L);
        when(stringRedisTemplate.opsForValue()).thenReturn(values);
        when(values.get(UserAccessState.key(10L))).thenReturn("ACTIVE:0");

        mockMvc.perform(get("/api/v1/catalog/products")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().string("products-data"));
    }

    @Test
    @DisplayName("ServiceSecurityAutoConfiguration cho phép truy cập /api/v1/auth/**, /api/v1/tenants/onboarding và /error không cần Token")
    void testPublicRoutesPermittedWithoutToken() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login"))
                .andExpect(status().isOk())
                .andExpect(content().string("login-ok"));

        mockMvc.perform(post("/api/v1/tenants/onboarding"))
                .andExpect(status().isOk())
                .andExpect(content().string("onboarding-ok"));

        mockMvc.perform(get("/error"))
                .andExpect(status().isOk())
                .andExpect(content().string("error-ok"));
    }
}
