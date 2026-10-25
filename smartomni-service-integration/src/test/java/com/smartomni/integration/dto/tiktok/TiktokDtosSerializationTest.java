package com.smartomni.integration.dto.tiktok;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TikTok Shop DTOs Jackson Serialization / Deserialization Tests")
class TiktokDtosSerializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("TiktokWebhookPayload: Deserialization thành công từ JSON chuẩn TikTok Webhook")
    void testTiktokWebhookPayload_Deserialization() throws Exception {
        String json = """
            {
              "tts_notification_id": "notif-123456",
              "type": 1,
              "shop_id": "7000000000000000001",
              "timestamp": 1700000000,
              "data": {
                "order_id": "576000000000000001",
                "order_status": "AWAITING_SHIPMENT"
              }
            }
            """;

        TiktokWebhookPayload payload = objectMapper.readValue(json, TiktokWebhookPayload.class);

        assertNotNull(payload);
        assertEquals("notif-123456", payload.getTtsNotificationId());
        assertEquals(1, payload.getType());
        assertEquals("7000000000000000001", payload.getShopId());
        assertEquals(1700000000L, payload.getTimestamp());
        assertNotNull(payload.getData());
        assertEquals("576000000000000001", payload.getData().get("order_id").asText());
        assertEquals("AWAITING_SHIPMENT", payload.getData().get("order_status").asText());
    }

    @Test
    @DisplayName("TiktokOrderDetailResponse: Deserialization thành công với Order Detail đầy đủ")
    void testTiktokOrderDetailResponse_Deserialization() throws Exception {
        String json = """
            {
              "order_id": "576000000000000001",
              "order_status": "AWAITING_SHIPMENT",
              "payment": {
                "currency": "VND",
                "sub_total": 250000.00,
                "shipping_fee": 30000.00,
                "seller_discount": 20000.00,
                "total_amount": 260000.00
              },
              "recipient_address": {
                "name": "Nguyen Van A",
                "phone_number": "0987654321",
                "full_address": "123 Le Loi, Quan 1, TP Ho Chi Minh",
                "city": "Ho Chi Minh City"
              },
              "line_items": [
                {
                  "id": "item-001",
                  "product_id": "prod-100",
                  "sku_id": "sku-200",
                  "seller_sku": "SKU-AO-THUN-XL",
                  "quantity": 2,
                  "sale_price": 125000.00,
                  "package_id": "pkg-999"
                }
              ],
              "package_id": "pkg-999"
            }
            """;

        TiktokOrderDetailResponse order = objectMapper.readValue(json, TiktokOrderDetailResponse.class);

        assertNotNull(order);
        assertEquals("576000000000000001", order.getOrderId());
        assertEquals("AWAITING_SHIPMENT", order.getOrderStatus());
        assertNotNull(order.getPayment());
        assertEquals("VND", order.getPayment().getCurrency());
        assertEquals(new BigDecimal("260000.00"), order.getPayment().getTotalAmount());
        assertNotNull(order.getRecipientAddress());
        assertEquals("Nguyen Van A", order.getRecipientAddress().getName());
        assertEquals("0987654321", order.getRecipientAddress().getPhoneNumber());
        assertNotNull(order.getLineItems());
        assertEquals(1, order.getLineItems().size());
        assertEquals("SKU-AO-THUN-XL", order.getLineItems().get(0).getSellerSku());
        assertEquals(2, order.getLineItems().get(0).getQuantity());
        assertEquals("pkg-999", order.getPackageId());
    }

    @Test
    @DisplayName("TiktokPackageResponse: Deserialization thành công từ JSON Package")
    void testTiktokPackageResponse_Deserialization() throws Exception {
        String json = """
            {
              "package_id": "pkg-1153000000001",
              "tracking_number": "TRACK-VN-987654321",
              "shipping_provider_name": "J&T Express",
              "shipping_provider_id": "SP-001",
              "status": "SHIPPED",
              "package_status": "SHIPPED",
              "order_id_list": ["576000000000000001"]
            }
            """;

        TiktokPackageResponse pkg = objectMapper.readValue(json, TiktokPackageResponse.class);

        assertNotNull(pkg);
        assertEquals("pkg-1153000000001", pkg.getPackageId());
        assertEquals("TRACK-VN-987654321", pkg.getTrackingNumber());
        assertEquals("J&T Express", pkg.getShippingProviderName());
        assertEquals("SHIPPED", pkg.getStatus());
        assertEquals(List.of("576000000000000001"), pkg.getOrderIdList());
    }

    @Test
    @DisplayName("TiktokReturnResponse: Deserialization thành công từ JSON Return/Refund")
    void testTiktokReturnResponse_Deserialization() throws Exception {
        String json = """
            {
              "return_id": "ret-99887766",
              "order_id": "576000000000000001",
              "return_status": "RETURN_REQUESTED",
              "return_reason": "DEFECTIVE_PRODUCT",
              "return_reason_text": "San pham bi loi may",
              "tracking_number": "RET-TRACK-112233",
              "refund_amount": 250000.00,
              "refund_currency": "VND",
              "return_line_items": [
                {
                  "return_item_id": "ret-item-1",
                  "product_id": "prod-100",
                  "seller_sku": "SKU-AO-THUN-XL",
                  "quantity": 1,
                  "refund_amount": 125000.00
                }
              ]
            }
            """;

        TiktokReturnResponse ret = objectMapper.readValue(json, TiktokReturnResponse.class);

        assertNotNull(ret);
        assertEquals("ret-99887766", ret.getReturnId());
        assertEquals("576000000000000001", ret.getOrderId());
        assertEquals("RETURN_REQUESTED", ret.getReturnStatus());
        assertEquals("DEFECTIVE_PRODUCT", ret.getReturnReason());
        assertEquals("RET-TRACK-112233", ret.getTrackingNumber());
        assertEquals(new BigDecimal("250000.00"), ret.getRefundAmount());
        assertNotNull(ret.getReturnLineItems());
        assertEquals(1, ret.getReturnLineItems().size());
        assertEquals("SKU-AO-THUN-XL", ret.getReturnLineItems().get(0).getSellerSku());
    }
}
