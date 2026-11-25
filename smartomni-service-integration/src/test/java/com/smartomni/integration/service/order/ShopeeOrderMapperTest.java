package com.smartomni.integration.service.order;

import com.smartomni.integration.client.shopee.ShopeeOrderClient;
import com.smartomni.integration.client.shopee.ShopeeOrderClient.OrderItemDetail;
import com.smartomni.integration.client.shopee.ShopeeOrderClient.RecipientAddress;
import com.smartomni.integration.client.shopee.ShopeeOrderClient.ShopeeOrderDetail;
import com.smartomni.common.dto.WebhookOrderPayload;
import com.smartomni.integration.service.catalog.CatalogMappingStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("ShopeeOrderMapper Unit Tests")
class ShopeeOrderMapperTest {

    private CatalogMappingStore store;
    private ShopeeOrderMapper mapper;

    @BeforeEach
    void setUp() {
        store = mock(CatalogMappingStore.class);
        mapper = new ShopeeOrderMapper(store);
    }

    @Test
    @DisplayName("toPayload maps all fields and resolves skuId via marketplace link")
    void toPayload_ResolvesSkuViaLink() {
        CatalogMappingStore.LinkRef link = new CatalogMappingStore.LinkRef(
                1L, 501L, 101L, 88L, "9001", "1001", "WM-BLK",
                new BigDecimal("225000"), "VND", Instant.now()
        );
        when(store.findLinkByExternalSku(88L, "9001")).thenReturn(Optional.of(link));

        RecipientAddress address = new RecipientAddress(
                "Nguyen Van A", "0901234567", "123 Le Loi", "HCMC", "District 1", "HCMC", "70000"
        );
        OrderItemDetail item = new OrderItemDetail(
                1001L, "Wireless Mouse", "WM-01", 9001L, "Black", "WM-BLK",
                2, new BigDecimal("250000"), new BigDecimal("225000"), 8801L
        );
        ShopeeOrderDetail order = new ShopeeOrderDetail(
                "241027SP1042", "READY_TO_SHIP", "VND", new BigDecimal("450000"),
                "nguyen_buyer", 9911L, address, List.of(item),
                1714173300L, 1714000000L, 1714000500L, null, null, "SPX Express", "SPX_1042"
        );

        WebhookOrderPayload payload = mapper.toPayload(1L, 88L, order);

        assertEquals(1L, payload.getTenantId());
        assertEquals("SHOPEE", payload.getPlatform());
        assertEquals("241027SP1042", payload.getPlatformOrderId());
        // Raw Shopee status: the mapping lives only in order-service (ShopeeOrderStatusMapperTest replaces
        // the former toCanonicalStatus matrix).
        assertEquals("READY_TO_SHIP", payload.getOrderStatus());
        assertEquals(1714000500L, payload.getPlatformUpdateTime());
        assertNull(payload.getReconciliationReason());
        assertEquals("Nguyen Van A", payload.getCustomerName());
        assertEquals("0901234567", payload.getCustomerPhone());
        assertEquals("123 Le Loi", payload.getShippingAddress());
        assertEquals(new BigDecimal("450000"), payload.getTotalAmount());

        assertEquals(1, payload.getItems().size());
        WebhookOrderPayload.OrderItemPayload itemPayload = payload.getItems().get(0);
        assertEquals(501L, itemPayload.getSkuId());
        assertEquals("WM-BLK", itemPayload.getSellerSku());
        assertEquals(2, itemPayload.getQuantity());
        assertEquals(new BigDecimal("225000"), itemPayload.getUnitPrice());
    }

    @Test
    @DisplayName("toPayload resolves skuId via seller SKU code fallback when link is absent")
    void toPayload_ResolvesSkuViaSkuCodeFallback() {
        when(store.findLinkByExternalSku(88L, "9001")).thenReturn(Optional.empty());

        CatalogMappingStore.SkuRef skuRef = new CatalogMappingStore.SkuRef(702L, 102L, "WM-BLK");
        when(store.findSkuByCode("WM-BLK")).thenReturn(Optional.of(skuRef));

        OrderItemDetail item = new OrderItemDetail(
                1001L, "Mouse", "WM-01", 9001L, "Black", "WM-BLK",
                1, new BigDecimal("200000"), new BigDecimal("200000"), 8802L
        );
        ShopeeOrderDetail order = new ShopeeOrderDetail(
                "241027SP9999", "UNPAID", "VND", new BigDecimal("200000"),
                "buyer2", 9912L, null, List.of(item),
                null, 1714000000L, 1714000000L, null, null, null, null
        );

        WebhookOrderPayload payload = mapper.toPayload(1L, 88L, order);

        assertEquals("UNPAID", payload.getOrderStatus());
        assertEquals(702L, payload.getItems().get(0).getSkuId());
    }

    @Test
    @DisplayName("Payload survives Jackson2JsonMessageConverter and is read by order-service with every field")
    void payloadRoundTripsThroughRabbitConverter() {
        when(store.findLinkByExternalSku(88L, "9001")).thenReturn(Optional.empty());
        OrderItemDetail item = new OrderItemDetail(
                1001L, "Mouse", "WM-01", 9001L, "Black", "WM-BLK",
                3, new BigDecimal("200000"), new BigDecimal("190000"), 8803L
        );
        ShopeeOrderDetail order = new ShopeeOrderDetail(
                "241027SP7777", "PROCESSED", "VND", new BigDecimal("570000"),
                "buyer3", 9913L, null, List.of(item),
                null, 1714000000L, 1714000900L, null, null, null, null
        );
        WebhookOrderPayload sent = mapper.toPayload(5L, 88L, order);

        var converter = new org.springframework.amqp.support.converter.Jackson2JsonMessageConverter();
        org.springframework.amqp.core.Message message =
                converter.toMessage(sent, new org.springframework.amqp.core.MessageProperties());
        String json = new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(json.contains("\"tenant_id\":5"), json);
        assertTrue(json.contains("\"order_status\":\"PROCESSED\""), json);

        WebhookOrderPayload received;
        try {
            received = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()
                    .readValue(message.getBody(), WebhookOrderPayload.class);
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        assertEquals(5L, received.getTenantId());
        assertEquals("SHOPEE", received.getPlatform());
        assertEquals("241027SP7777", received.getPlatformOrderId());
        assertEquals("PROCESSED", received.getOrderStatus());
        assertEquals(1714000900L, received.getPlatformUpdateTime());
        assertEquals(new BigDecimal("570000"), received.getTotalAmount());
        assertEquals("SKU_UNMAPPED", received.getReconciliationReason());
        assertEquals(1, received.getItems().size());
        assertNull(received.getItems().get(0).getSkuId());
        assertEquals("9001", received.getItems().get(0).getExternalSkuId());
        assertEquals(3, received.getItems().get(0).getQuantity());
        assertEquals(new BigDecimal("190000"), received.getItems().get(0).getUnitPrice());
    }
}
