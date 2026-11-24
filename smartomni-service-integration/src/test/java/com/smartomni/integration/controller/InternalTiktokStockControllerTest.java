package com.smartomni.integration.controller;

import com.smartomni.common.exception.BusinessException;
import com.smartomni.common.exception.GlobalExceptionHandler;
import com.smartomni.integration.client.tiktok.TiktokApiException;
import com.smartomni.integration.exception.TiktokApiExceptionHandler;
import com.smartomni.integration.service.tiktok.TiktokStockPushService;
import org.springframework.http.HttpStatus;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InternalTiktokStockControllerTest {
    @Test
    void forwardsZeroStockToTikTokPushService() throws Exception {
        TiktokStockPushService service = mock(TiktokStockPushService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new InternalTiktokStockController(service)).build();

        mvc.perform(post("/internal/jobs/push-tiktok-stock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":1,\"skuId\":9,\"quantity\":0}"))
                .andExpect(status().isNoContent());

        verify(service).push(1, 9, 0);
    }

    @Test
    void platformAndInputFailuresAnswerNon2xx() throws Exception {
        TiktokStockPushService service = mock(TiktokStockPushService.class);
        doThrow(new TiktokApiException("TikTok rejected SKU inventory update", 200, 1, null, false))
                .when(service).push(1, 9, 5);
        doThrow(new TiktokApiException("timeout", 0, null, null, true)).when(service).push(1, 9, 6);
        doThrow(new BusinessException("No connected TikTok mapping", HttpStatus.CONFLICT, "TIKTOK_SKU_NOT_MAPPED"))
                .when(service).push(1, 9, 7);
        var mvc = MockMvcBuilders.standaloneSetup(new InternalTiktokStockController(service))
                .setControllerAdvice(new TiktokApiExceptionHandler(), new GlobalExceptionHandler()).build();

        mvc.perform(post("/internal/jobs/push-tiktok-stock").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":1,\"skuId\":9,\"quantity\":5}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TIKTOK_REQUEST_REJECTED"));
        mvc.perform(post("/internal/jobs/push-tiktok-stock").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":1,\"skuId\":9,\"quantity\":6}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("TIKTOK_UNAVAILABLE"));
        mvc.perform(post("/internal/jobs/push-tiktok-stock").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":1,\"skuId\":9,\"quantity\":7}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TIKTOK_SKU_NOT_MAPPED"));
    }
}
