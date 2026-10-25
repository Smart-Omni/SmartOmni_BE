package com.smartomni.integration.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * DTO chuẩn hóa payload đơn hàng gửi vào RabbitMQ ('smartomni.order.exchange')
 * để Order Service tiêu thụ và lưu trữ.
 * Kế thừa từ com.smartomni.common.dto.WebhookOrderPayload.
 */
@Getter
@Setter
public class WebhookOrderPayload extends com.smartomni.common.dto.WebhookOrderPayload {
}
