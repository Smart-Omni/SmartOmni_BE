package com.smartomni.integration.dto.tiktok;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO đại diện cho Webhook notification payload gửi từ TikTok Shop Open Platform.
 * Định dạng:
 * {
 *   "tts_notification_id": "...",
 *   "type": 1,
 *   "shop_id": "...",
 *   "timestamp": 1700000000,
 *   "data": { ... }
 * }
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class TiktokWebhookPayload {

    @JsonProperty("tts_notification_id")
    private String ttsNotificationId;

    /**
     * Mã phân loại loại sự kiện (Event Type code) của TikTok.
     * Ví dụ:
     * 1: ORDER_STATUS_CHANGE
     * 2: REVERSE_STATUS_CHANGE / RETURN
     * 3: PACKAGE_UPDATE
     */
    @JsonProperty("type")
    private Integer type;

    @JsonProperty("shop_id")
    private String shopId;

    @JsonProperty("timestamp")
    private Long timestamp;

    /**
     * Dữ liệu chi tiết của sự kiện (thay đổi linh hoạt tùy theo event type).
     */
    @JsonProperty("data")
    private JsonNode data;
}
