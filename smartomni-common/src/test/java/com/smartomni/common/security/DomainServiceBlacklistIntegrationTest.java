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

    @Test
    @DisplayName("Domain service từ chối request khi token nằm trong Redis Blacklist (HTTP 401 hoặc 403)")
    void testDomainServiceRejectsBlacklistedToken() throws Exception {
        String token = "blacklisted-token";
        when(stringRedisTemplate.hasKey(AppConstants.REDIS_PREFIX_BLACKLIST + token)).thenReturn(true);

        mockMvc.perform(get("/api/v1/catalog/products")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Domain service chấp nhận request khi token hợp lệ và không nằm trong Redis Blacklist (HTTP 200)")
    void testDomainServiceAcceptsValidNonBlacklistedToken() throws Exception {
        String token = "valid-domain-token";
        when(stringRedisTemplate.hasKey(AppConstants.REDIS_PREFIX_BLACKLIST + token)).thenReturn(false);
        when(jwtTokenProvider.validateToken(token)).thenReturn(true);
        when(jwtTokenProvider.getUserId(token)).thenReturn(10L);
        when(jwtTokenProvider.getRole(token)).thenReturn("ADMIN");
        when(jwtTokenProvider.getTenantId(token)).thenReturn(100L);

        mockMvc.perform(get("/api/v1/catalog/products")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().string("products-data"));
    }
}
