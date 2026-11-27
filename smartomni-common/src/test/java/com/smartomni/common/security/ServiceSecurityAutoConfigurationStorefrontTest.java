package com.smartomni.common.security;

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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Shoppers read the public storefront catalog without a JWT; the merchant catalog still needs one. */
@WebMvcTest(controllers = ServiceSecurityAutoConfigurationStorefrontTest.FakeCatalogController.class)
@ContextConfiguration(classes = ServiceSecurityAutoConfigurationStorefrontTest.TestConfig.class)
class ServiceSecurityAutoConfigurationStorefrontTest {

    @RestController
    static class FakeCatalogController {
        @GetMapping("/api/v1/products/public/by-subdomain/shop")
        public String publicList() {
            return "public-ok";
        }

        @PostMapping("/api/v1/products/public/by-subdomain/shop")
        public String publicPost() {
            return "post-ok";
        }

        @GetMapping("/api/v1/products")
        public String merchantList() {
            return "merchant-ok";
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(ServiceSecurityAutoConfiguration.class)
    static class TestConfig {
        @Bean
        public FakeCatalogController fakeCatalogController() {
            return new FakeCatalogController();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    @MockBean
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void anonymousShopperCanReadPublicCatalog() throws Exception {
        mockMvc.perform(get("/api/v1/products/public/by-subdomain/shop"))
                .andExpect(status().isOk())
                .andExpect(content().string("public-ok"));
    }

    @Test
    void merchantCatalogAndWritesStillRequireJwt() throws Exception {
        mockMvc.perform(get("/api/v1/products")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/products/public/by-subdomain/shop")).andExpect(status().isUnauthorized());
    }
}
