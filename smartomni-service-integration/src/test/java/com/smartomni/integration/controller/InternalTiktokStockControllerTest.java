package com.smartomni.integration.controller;

import com.smartomni.integration.service.tiktok.TiktokStockPushService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
}
