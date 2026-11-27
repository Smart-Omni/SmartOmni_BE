package com.smartomni.integration.dto.tiktok;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TiktokOrderDetailResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("rts_sla_time from TikTok is passed through to the API response")
    void keepsShipDeadline() throws Exception {
        TiktokOrderDetailResponse order = objectMapper.readValue(
                "{\"id\":\"TK-1\",\"status\":\"AWAITING_SHIPMENT\",\"rts_sla_time\":1700172900}",
                TiktokOrderDetailResponse.class);

        assertEquals(1700172900L, order.getRtsSlaTime());
        assertTrue(objectMapper.writeValueAsString(order).contains("\"rts_sla_time\":1700172900"));
    }
}
