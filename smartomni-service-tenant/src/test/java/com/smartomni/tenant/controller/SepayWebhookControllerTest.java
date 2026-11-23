package com.smartomni.tenant.controller;

import com.smartomni.common.exception.GlobalExceptionHandler;
import com.smartomni.common.security.JwtTokenProvider;
import com.smartomni.tenant.config.SepayProperties;
import com.smartomni.tenant.service.SubscriptionService;
import com.smartomni.tenant.util.SepaySignatureValidator;
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
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SepayWebhookController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
@DisplayName("SepayWebhookController WebMvc Tests")
class SepayWebhookControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SubscriptionService subscriptionService;

    @MockBean
    private SepaySignatureValidator signatureValidator;

    @MockBean
    private SepayProperties sepayProperties;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    @MockBean
    private StringRedisTemplate stringRedisTemplate;

    @MockBean
    private JpaMetamodelMappingContext jpaMetamodelMappingContext;

    @Test
    @DisplayName("handleWebhook: Chữ ký hợp lệ và giao dịch xử lý thành công -> 200 OK với success=true")
    void handleWebhook_success() throws Exception {
        String body = "{\"id\": 1001, \"transferAmount\": 499000}";
        when(signatureValidator.validate(any(), any(), any(), any())).thenReturn(true);
        when(subscriptionService.processWebhook(any(), any(), any())).thenReturn(true);

        mockMvc.perform(post("/webhook/sepay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-SePay-Signature", "sha256=validsig")
                        .header("X-SePay-Timestamp", "1700000000")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("handleWebhook: Chữ ký không hợp lệ -> 401 Unauthorized")
    void handleWebhook_invalidSignature_returns401() throws Exception {
        String body = "{\"id\": 1001}";
        when(signatureValidator.validate(any(), any(), any(), any())).thenReturn(false);

        mockMvc.perform(post("/webhook/sepay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-SePay-Signature", "sha256=invalidsig")
                        .header("X-SePay-Timestamp", "1700000000")
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("handleWebhook: Chữ ký hợp lệ nhưng giao dịch thiếu tiền/không khớp -> 200 OK với success=false theo SePay spec")
    void handleWebhook_unmatchedOrUnderpaid_returns200WithFalse() throws Exception {
        String body = "{\"id\": 1001, \"transferAmount\": 10000}";
        when(signatureValidator.validate(any(), any(), any(), any())).thenReturn(true);
        when(subscriptionService.processWebhook(any(), any(), any())).thenReturn(false);

        mockMvc.perform(post("/webhook/sepay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-SePay-Signature", "sha256=validsig")
                        .header("X-SePay-Timestamp", "1700000000")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("handleWebhook: Body rỗng -> 400 Bad Request")
    void handleWebhook_emptyBody_returns400() throws Exception {
        mockMvc.perform(post("/webhook/sepay")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }
}
