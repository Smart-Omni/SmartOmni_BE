package com.smartomni.order.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * DTO chuan hoa payload don hang tu Webhook Shopee/TikTok Shop (sau khi da
 * duoc chuan hoa boi smartomni-service-integration truoc khi day vao Queue).
 * Ke thua tu com.smartomni.common.dto.WebhookOrderPayload.
 * UC-32/UC-33.
 */
@Getter
@Setter
public class WebhookOrderPayload extends com.smartomni.common.dto.WebhookOrderPayload {
}
