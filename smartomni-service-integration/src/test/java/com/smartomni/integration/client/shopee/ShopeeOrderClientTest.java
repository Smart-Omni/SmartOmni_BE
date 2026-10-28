package com.smartomni.integration.client.shopee;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartomni.integration.config.ShopeeProperties;
import com.smartomni.integration.service.shopee.ShopeeShopApiExecutor;
import com.smartomni.integration.service.shopee.ShopeeTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("ShopeeOrderClient Unit Tests")
class ShopeeOrderClientTest {

    private static final Instant NOW = Instant.parse("2026-10-27T10:00:00Z");
    private FakeShopeeTransport transport;
    private ShopeeOrderClient client;

    @BeforeEach
    void setUp() {
        ShopeeProperties properties = new ShopeeProperties();
        properties.setPartnerId(1001141L);
        properties.setPartnerKey("shopee_mock_secret_key_2026");
        properties.setBaseUrl("http://shopee-simulator.test:8080");

        ShopeeTokenService tokenService = mock(ShopeeTokenService.class);
        when(tokenService.credentials(anyLong()))
                .thenReturn(new ShopeeTokenService.Credentials("AT-valid-123", 727720655L, 1L));

        ObjectMapper mapper = new ObjectMapper();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

        transport = new FakeShopeeTransport(s -> {
            String path = s.uri().getPath();
            if (path.contains("/order/get_order_list")) {
                return FakeShopeeTransport.ok("""
                    {
                      "more": false,
                      "next_cursor": "1",
                      "order_list": [
                        { "order_sn": "241027SP1042", "order_status": "READY_TO_SHIP" }
                      ]
                    }
                    """);
            }
            if (path.contains("/order/get_order_detail")) {
                return FakeShopeeTransport.ok("""
                    {
                      "order_list": [
                        {
                          "order_sn": "241027SP1042",
                          "order_status": "READY_TO_SHIP",
                          "currency": "VND",
                          "total_amount": 450000,
                          "buyer_username": "nguyen_buyer",
                          "buyer_user_id": 9911,
                          "create_time": 1714000000,
                          "update_time": 1714000500,
                          "ship_by_date": 1714173300,
                          "recipient_address": {
                            "name": "Nguyen Van A",
                            "phone": "0901234567",
                            "full_address": "123 Le Loi, District 1, HCMC"
                          },
                          "item_list": [
                            {
                              "item_id": 1001,
                              "item_name": "Wireless Mouse",
                              "item_sku": "WM-01",
                              "model_id": 9001,
                              "model_name": "Black",
                              "model_sku": "WM-BLK",
                              "model_quantity_purchased": 2,
                              "model_original_price": 250000,
                              "model_discounted_price": 225000,
                              "order_item_id": 8801
                            }
                          ],
                          "package_list": [
                            {
                              "tracking_number": "SPX_VN_1042",
                              "shipping_carrier": "SPX Express"
                            }
                          ]
                        }
                      ]
                    }
                    """);
            }
            if (path.contains("/order/cancel_order")) {
                return FakeShopeeTransport.ok("{\"result\":\"success\"}");
            }
            if (path.contains("/order/handle_buyer_cancellation")) {
                return FakeShopeeTransport.ok("{\"result\":\"success\"}");
            }
            return FakeShopeeTransport.ok("{}");
        });

        ShopeeShopApiExecutor executor = new ShopeeShopApiExecutor(tokenService, properties, transport, clock, mapper);
        client = new ShopeeOrderClient(executor, mapper);
    }

    @Test
    @DisplayName("getOrderList sends signed shop request and parses orders and cursor")
    void getOrderList_Success() {
        ShopeeOrderClient.OrderListResult result = client.getOrderList(
                88L, 1714000000L, 1714100000L, 20, "0", "READY_TO_SHIP"
        );

        assertFalse(result.more());
        assertEquals("1", result.nextCursor());
        assertEquals(1, result.orders().size());
        assertEquals("241027SP1042", result.orders().get(0).orderSn());
        assertEquals("READY_TO_SHIP", result.orders().get(0).orderStatus());

        FakeShopeeTransport.Sent sent = transport.sent.get(0);
        assertEquals("/api/v2/order/get_order_list", sent.uri().getPath());
        String q = URLDecoder.decode(sent.uri().getRawQuery(), StandardCharsets.UTF_8);
        assertTrue(q.contains("shop_id=727720655"));
        assertTrue(q.contains("access_token=AT-valid-123"));
        assertTrue(q.contains("order_status=READY_TO_SHIP"));
        assertTrue(q.contains("sign="));
    }

    @Test
    @DisplayName("getOrderDetail sends batch order_sn_list and parses full details")
    void getOrderDetail_Success() {
        List<ShopeeOrderClient.ShopeeOrderDetail> details = client.getOrderDetail(88L, List.of("241027SP1042"));

        assertEquals(1, details.size());
        ShopeeOrderClient.ShopeeOrderDetail order = details.get(0);
        assertEquals("241027SP1042", order.orderSn());
        assertEquals("READY_TO_SHIP", order.orderStatus());
        assertEquals("VND", order.currency());
        assertEquals(BigDecimal.valueOf(450000.0), order.totalAmount());
        assertEquals("nguyen_buyer", order.buyerUsername());
        assertEquals("Nguyen Van A", order.recipientAddress().name());
        assertEquals("0901234567", order.recipientAddress().phone());
        assertEquals("123 Le Loi, District 1, HCMC", order.recipientAddress().fullAddress());
        assertEquals("SPX_VN_1042", order.trackingNo());
        assertEquals("SPX Express", order.shippingCarrier());

        assertEquals(1, order.items().size());
        ShopeeOrderClient.OrderItemDetail item = order.items().get(0);
        assertEquals(1001L, item.itemId());
        assertEquals(9001L, item.modelId());
        assertEquals("WM-BLK", item.modelSku());
        assertEquals(2, item.quantityPurchased());
        assertEquals(BigDecimal.valueOf(225000.0), item.discountedPrice());
    }

    @Test
    @DisplayName("cancelOrder sends POST with order_sn and cancel_reason")
    void cancelOrder_Success() {
        boolean success = client.cancelOrder(88L, "241027SP1042", "OUT_OF_STOCK");

        assertTrue(success);
        FakeShopeeTransport.Sent sent = transport.sent.get(0);
        assertEquals("/api/v2/order/cancel_order", sent.uri().getPath());
        assertTrue(sent.body().contains("\"order_sn\":\"241027SP1042\""));
        assertTrue(sent.body().contains("\"cancel_reason\":\"OUT_OF_STOCK\""));
    }

    @Test
    @DisplayName("handleBuyerCancellation sends POST with order_sn and operation")
    void handleBuyerCancellation_Success() {
        boolean success = client.handleBuyerCancellation(88L, "241027SP1042", "ACCEPT");

        assertTrue(success);
        FakeShopeeTransport.Sent sent = transport.sent.get(0);
        assertEquals("/api/v2/order/handle_buyer_cancellation", sent.uri().getPath());
        assertTrue(sent.body().contains("\"order_sn\":\"241027SP1042\""));
        assertTrue(sent.body().contains("\"operation\":\"ACCEPT\""));
    }
}
