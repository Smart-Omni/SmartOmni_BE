package com.smartomni.integration.service.tiktok;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartomni.common.dto.WebhookOrderPayload;
import com.smartomni.common.exception.BusinessException;
import com.smartomni.integration.dto.tiktok.TiktokOrderDetailResponse;
import com.smartomni.integration.dto.tiktok.TiktokWebhookPayload;
import com.smartomni.integration.service.catalog.CatalogMappingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TiktokOrderIngestionService Unit Tests")
class TiktokOrderIngestionServiceTest {

    private static final Long TENANT_ID = 1L;
    private static final String ORDER_ID = "TT-ORDER-20261026-001";
    private static final Long CREATE_TIME = 1729900000L;

    @Mock
    private CatalogMappingService catalogMappingService;

    @Mock
    private RabbitTemplate rabbitTemplate;

    private ObjectMapper objectMapper;
    private TiktokOrderIngestionService ingestionService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        ingestionService = new TiktokOrderIngestionService(catalogMappingService, rabbitTemplate, objectMapper);
    }

    @Test
    @DisplayName("Chuyển đổi order thành công, map SKU chuẩn xác và publish vào RabbitMQ")
    void ingestOrder_success_allSkusMapped() {
        // Line items
        TiktokOrderDetailResponse.LineItem item1 = TiktokOrderDetailResponse.LineItem.builder()
                .id("ITEM-01")
                .skuId("EXT-SKU-100")
                .sellerSku("SELLER-SKU-100")
                .productName("Áo thun SmartOmni")
                .quantity(2)
                .salePrice(new BigDecimal("150000"))
                .build();

        TiktokOrderDetailResponse.LineItem item2 = TiktokOrderDetailResponse.LineItem.builder()
                .id("ITEM-02")
                .skuId("EXT-SKU-200")
                .sellerSku(null) // Chỉ có skuId
                .productName("Quần jean SmartOmni")
                .quantity(1)
                .salePrice(new BigDecimal("350000"))
                .build();

        TiktokOrderDetailResponse order = TiktokOrderDetailResponse.builder()
                .orderId(ORDER_ID)
                .orderStatus("AWAITING_SHIPMENT")
                .createTime(CREATE_TIME)
                .payment(TiktokOrderDetailResponse.Payment.builder()
                        .totalAmount(new BigDecimal("650000"))
                        .build())
                .recipientAddress(TiktokOrderDetailResponse.RecipientAddress.builder()
                        .name("Nguyen Van A")
                        .phoneNumber("0912345678")
                        .fullAddress("123 Nguyen Trai, Quan 1, TP Ho Chi Minh")
                        .build())
                .lineItems(List.of(item1, item2))
                .build();

        // Mock mapping
        when(catalogMappingService.findInternalSkuId(TENANT_ID, "tiktok_shop", "SELLER-SKU-100"))
                .thenReturn(Optional.of(1001L));
        when(catalogMappingService.findInternalSkuId(TENANT_ID, "tiktok_shop", "EXT-SKU-200"))
                .thenReturn(Optional.of(1002L));

        // Execute
        WebhookOrderPayload result = ingestionService.ingestOrder(TENANT_ID, order);

        // Assertions
        assertNotNull(result);
        assertEquals(TENANT_ID, result.getTenantId());
        assertEquals("TIKTOK_SHOP", result.getPlatform());
        assertEquals(ORDER_ID, result.getPlatformOrderId());
        assertEquals("AWAITING_SHIPMENT", result.getOrderStatus());
        assertEquals("Nguyen Van A", result.getCustomerName());
        assertEquals("0912345678", result.getCustomerPhone());
        assertEquals("123 Nguyen Trai, Quan 1, TP Ho Chi Minh", result.getShippingAddress());
        assertEquals(new BigDecimal("650000"), result.getTotalAmount());
        assertEquals(CREATE_TIME, result.getCreateTime());
        assertEquals(Instant.ofEpochSecond(CREATE_TIME), result.getCreatedAt());

        assertEquals(2, result.getItems().size());
        assertEquals(1001L, result.getItems().get(0).getSkuId());
        assertEquals("SELLER-SKU-100", result.getItems().get(0).getSellerSku());
        assertEquals(2, result.getItems().get(0).getQuantity());
        assertEquals(new BigDecimal("150000"), result.getItems().get(0).getUnitPrice());

        assertEquals(1002L, result.getItems().get(1).getSkuId());
        assertEquals("EXT-SKU-200", result.getItems().get(1).getExternalSkuId());
        assertEquals(1, result.getItems().get(1).getQuantity());

        // Verify RabbitTemplate call
        ArgumentCaptor<WebhookOrderPayload> payloadCaptor = ArgumentCaptor.forClass(WebhookOrderPayload.class);
        verify(rabbitTemplate).convertAndSend(
                eq(TiktokOrderIngestionService.ORDER_EXCHANGE),
                eq(TiktokOrderIngestionService.ORDER_ROUTING_KEY),
                payloadCaptor.capture()
        );
        assertEquals(ORDER_ID, payloadCaptor.getValue().getPlatformOrderId());
    }

    @Test
    @DisplayName("Xử lý gracefully khi SKU chưa được ánh xạ (skuId là null, log warn và không throw exception)")
    void ingestOrder_gracefulHandling_whenSkuUnmapped() {
        TiktokOrderDetailResponse.LineItem unmappedItem = TiktokOrderDetailResponse.LineItem.builder()
                .id("ITEM-UNMAPPED")
                .skuId("EXT-UNKNOWN")
                .sellerSku("SELLER-UNKNOWN")
                .productName("Sản phẩm chưa link")
                .quantity(1)
                .salePrice(new BigDecimal("99000"))
                .build();

        TiktokOrderDetailResponse order = TiktokOrderDetailResponse.builder()
                .orderId(ORDER_ID)
                .orderStatus("AWAITING_SHIPMENT")
                .createTime(CREATE_TIME)
                .lineItems(List.of(unmappedItem))
                .build();

        // CatalogMappingService returns empty
        when(catalogMappingService.findInternalSkuId(anyLong(), anyString(), anyString()))
                .thenReturn(Optional.empty());

        // Execute: should NOT throw exception
        WebhookOrderPayload result = ingestionService.ingestOrder(TENANT_ID, order);

        assertNotNull(result);
        assertEquals(1, result.getItems().size());
        assertNull(result.getItems().get(0).getSkuId(), "skuId phải là null khi chưa ánh xạ");
        assertEquals("SELLER-UNKNOWN", result.getItems().get(0).getSellerSku());
        assertEquals("EXT-UNKNOWN", result.getItems().get(0).getExternalSkuId());

        // Message still published to RabbitMQ
        verify(rabbitTemplate).convertAndSend(
                eq(TiktokOrderIngestionService.ORDER_EXCHANGE),
                eq(TiktokOrderIngestionService.ORDER_ROUTING_KEY),
                any(WebhookOrderPayload.class)
        );
    }

    @Test
    @DisplayName("Xử lý Webhook payload sự kiện ORDER_STATUS_CHANGE thành công")
    void processOrderWebhook_success() throws Exception {
        String jsonWebhookData = """
                {
                    "order_id": "TT-ORDER-WEBHOOK-99",
                    "order_status": "AWAITING_SHIPMENT",
                    "create_time": 1729905000,
                    "payment": {
                        "total_amount": 250000
                    },
                    "recipient_address": {
                        "name": "Tran Van B",
                        "phone_number": "0987654321",
                        "address_line1": "456 Le Loi",
                        "city": "Da Nang"
                    },
                    "line_items": [
                        {
                            "id": "LI-01",
                            "sku_id": "EXT-SKU-99",
                            "seller_sku": "SELLER-SKU-99",
                            "product_name": "Non bao hiem",
                            "quantity": 1,
                            "sale_price": 250000
                        }
                    ]
                }
                """;

        JsonNode dataNode = objectMapper.readTree(jsonWebhookData);
        TiktokWebhookPayload webhookPayload = TiktokWebhookPayload.builder()
                .ttsNotificationId("TTS-NOTIF-001")
                .type(1)
                .shopId("TEST_SHOP_1")
                .timestamp(1729905010L)
                .data(dataNode)
                .build();

        when(catalogMappingService.findInternalSkuId(TENANT_ID, "tiktok_shop", "SELLER-SKU-99"))
                .thenReturn(Optional.of(888L));

        WebhookOrderPayload result = ingestionService.processOrderWebhook(TENANT_ID, webhookPayload);

        assertNotNull(result);
        assertEquals("TT-ORDER-WEBHOOK-99", result.getPlatformOrderId());
        assertEquals("Tran Van B", result.getCustomerName());
        assertEquals("456 Le Loi, Da Nang", result.getShippingAddress());
        assertEquals(1, result.getItems().size());
        assertEquals(888L, result.getItems().get(0).getSkuId());

        verify(rabbitTemplate).convertAndSend(
                eq(TiktokOrderIngestionService.ORDER_EXCHANGE),
                eq(TiktokOrderIngestionService.ORDER_ROUTING_KEY),
                any(WebhookOrderPayload.class)
        );
    }

    @Test
    @DisplayName("Ném BusinessException khi orderId rỗng hoặc tenantId không hợp lệ")
    void ingestOrder_throwsException_whenInvalidArguments() {
        // Invalid tenantId
        assertThrows(BusinessException.class, () ->
                ingestionService.ingestOrder((Long) null, TiktokOrderDetailResponse.builder().orderId("123").build())
        );

        // Missing order_id
        assertThrows(BusinessException.class, () ->
                ingestionService.ingestOrder(TENANT_ID, TiktokOrderDetailResponse.builder().orderId(null).build())
        );
    }

    @Test
    @DisplayName("Ném BusinessException khi RabbitMQ publish thất bại")
    void ingestOrder_throwsException_whenRabbitFails() {
        TiktokOrderDetailResponse order = TiktokOrderDetailResponse.builder()
                .orderId(ORDER_ID)
                .orderStatus("AWAITING_SHIPMENT")
                .createTime(CREATE_TIME)
                .build();

        doThrow(new AmqpException("Broker connection refused"))
                .when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(Object.class));

        BusinessException exception = assertThrows(BusinessException.class, () ->
                ingestionService.ingestOrder(TENANT_ID, order)
        );

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, exception.getHttpStatus());
        assertEquals("QUEUE_PUBLISH_FAILED", exception.getErrorCode());
    }
}
