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

/** The TikTok webhook POST reaches its controller without a JWT; everything else still needs one. */
@WebMvcTest(controllers = ServiceSecurityAutoConfigurationWebhookTest.FakeWebhookController.class)
@ContextConfiguration(classes = ServiceSecurityAutoConfigurationWebhookTest.TestConfig.class)
class ServiceSecurityAutoConfigurationWebhookTest {

    @RestController
    static class FakeWebhookController {
        @PostMapping(ServiceSecurityAutoConfiguration.TIKTOK_WEBHOOK_PATH)
        public String webhook() {
            return "webhook-ok";
        }

        @GetMapping(ServiceSecurityAutoConfiguration.TIKTOK_WEBHOOK_PATH)
        public String webhookGet() {
            return "get-ok";
        }

        @PostMapping("/api/v1/integrations/tiktok/other")
        public String other() {
            return "other-ok";
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(ServiceSecurityAutoConfiguration.class)
    static class TestConfig {
        @Bean
        public FakeWebhookController fakeWebhookController() {
            return new FakeWebhookController();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    @MockBean
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void tiktokWebhookPostWithoutJwtIsNotRejected() throws Exception {
        // TikTok puts the HMAC signature (not a Bearer token) in Authorization.
        mockMvc.perform(post(ServiceSecurityAutoConfiguration.TIKTOK_WEBHOOK_PATH)
                        .header("Authorization", "3f1c0d9e8b7a")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk())
                .andExpect(content().string("webhook-ok"));
        mockMvc.perform(post(ServiceSecurityAutoConfiguration.TIKTOK_WEBHOOK_PATH)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    void otherPathsAndMethodsStillRequireJwt() throws Exception {
        mockMvc.perform(post("/api/v1/integrations/tiktok/other")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(ServiceSecurityAutoConfiguration.TIKTOK_WEBHOOK_PATH)).andExpect(status().isUnauthorized());
    }
}
