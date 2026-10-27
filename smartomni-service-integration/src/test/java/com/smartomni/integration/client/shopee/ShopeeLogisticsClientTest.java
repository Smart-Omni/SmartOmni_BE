package com.smartomni.integration.client.shopee;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartomni.integration.config.ShopeeProperties;
import com.smartomni.integration.service.shopee.ShopeeShopApiExecutor;
import com.smartomni.integration.service.shopee.ShopeeTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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

@DisplayName("ShopeeLogisticsClient Unit Tests")
class ShopeeLogisticsClientTest {

    private static final Instant NOW = Instant.parse("2026-10-27T10:00:00Z");
    private FakeShopeeTransport transport;
    private ShopeeLogisticsClient client;

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
            if (path.contains("/logistics/get_channel_list")) {
                return FakeShopeeTransport.ok("""
                    {
                      "logistics_channel_list": [
                        {
                          "logistics_channel_id": 30001,
                          "logistics_channel_name": "SPX Express",
                          "cod_enabled": true,
                          "enabled": true,
                          "fee_type": "CUSTOM_PRICE"
                        }
                      ]
                    }
                    """);
            }
            if (path.contains("/logistics/get_shipping_parameter")) {
                return FakeShopeeTransport.ok("""
                    {
                      "info_needed": {
                        "dropoff": ["branch_id"],
                        "pickup": ["address_id", "pickup_time_id"]
                      },
                      "dropoff": {
                        "branch_list": [
                          {
                            "branch_id": 101,
                            "address": "123 Xuan Thuy",
                            "city": "Hanoi"
                          }
                        ]
                      },
                      "pickup": {
                        "address_list": [
                          {
                            "address_id": 901,
                            "address": "456 Kim Ma",
                            "city": "Hanoi",
                            "time_slot_list": [
                              {
                                "pickup_time_id": "slot_morning",
                                "date": 1714100000,
                                "time_text": "09:00 - 12:00"
                              }
                            ]
                          }
                        ]
                      }
                    }
                    """);
            }
            if (path.contains("/logistics/ship_order")) {
                return FakeShopeeTransport.ok("{}");
            }
            if (path.contains("/logistics/get_tracking_number")) {
                return FakeShopeeTransport.ok("""
                    {
                      "tracking_number": "SPXVN123456789"
                    }
                    """);
            }
            if (path.contains("/logistics/create_shipping_document")) {
                return FakeShopeeTransport.ok("""
                    {
                      "result_list": [
                        { "order_sn": "241027SP1042", "status": "READY" }
                      ]
                    }
                    """);
            }
            if (path.contains("/logistics/get_shipping_document_result")) {
                return FakeShopeeTransport.ok("""
                    {
                      "result_list": [
                        { "order_sn": "241027SP1042", "status": "READY" }
                      ]
                    }
                    """);
            }
            if (path.contains("/logistics/download_shipping_document")) {
                return new ShopeeHttpTransport.Response(200, "%PDF-1.4 mock binary pdf stream");
            }
            return FakeShopeeTransport.ok("{}");
        });

        ShopeeShopApiExecutor executor = new ShopeeShopApiExecutor(tokenService, properties, transport, clock, mapper);
        client = new ShopeeLogisticsClient(executor, mapper);
    }

    @Test
    @DisplayName("getChannelList retrieves supported logistics carriers")
    void getChannelList_Success() {
        List<ShopeeLogisticsClient.ChannelDetail> channels = client.getChannelList(88L);

        assertEquals(1, channels.size());
        assertEquals(30001L, channels.get(0).channelId());
        assertEquals("SPX Express", channels.get(0).channelName());
        assertTrue(channels.get(0).codEnabled());
    }

    @Test
    @DisplayName("getShippingParameter parses dropoff branches and pickup time slots")
    void getShippingParameter_Success() {
        ShopeeLogisticsClient.ShippingParameter param = client.getShippingParameter(88L, "241027SP1042");

        assertNotNull(param);
        assertEquals(1, param.dropoffBranches().size());
        assertEquals(101L, param.dropoffBranches().get(0).branchId());
        assertEquals(1, param.pickupAddresses().size());
        assertEquals(901L, param.pickupAddresses().get(0).addressId());
        assertEquals("slot_morning", param.pickupAddresses().get(0).timeSlots().get(0).pickupTimeId());
    }

    @Test
    @DisplayName("shipOrder sends POST with pickup parameters")
    void shipOrder_Pickup_Success() {
        ShopeeLogisticsClient.PickupParam pickup = new ShopeeLogisticsClient.PickupParam(901L, "slot_morning");
        ShopeeLogisticsClient.ShipOrderResult res = client.shipOrder(88L, "241027SP1042", null, pickup, null);

        assertTrue(res.success());
        FakeShopeeTransport.Sent sent = transport.sent.get(0);
        assertEquals("/api/v2/logistics/ship_order", sent.uri().getPath());
        assertTrue(sent.body().contains("\"order_sn\":\"241027SP1042\""));
        assertTrue(sent.body().contains("\"address_id\":901"));
        assertTrue(sent.body().contains("\"pickup_time_id\":\"slot_morning\""));
    }

    @Test
    @DisplayName("getTrackingNumber parses assigned carrier tracking number")
    void getTrackingNumber_Success() {
        String trackingNo = client.getTrackingNumber(88L, "241027SP1042", null);

        assertEquals("SPXVN123456789", trackingNo);
        FakeShopeeTransport.Sent sent = transport.sent.get(0);
        assertEquals("/api/v2/logistics/get_tracking_number", sent.uri().getPath());
        String q = URLDecoder.decode(sent.uri().getRawQuery(), StandardCharsets.UTF_8);
        assertTrue(q.contains("order_sn=241027SP1042"));
    }

    @Test
    @DisplayName("createShippingDocument requests AWB generation")
    void createShippingDocument_Success() {
        boolean ok = client.createShippingDocument(88L, "241027SP1042", null);

        assertTrue(ok);
        FakeShopeeTransport.Sent sent = transport.sent.get(0);
        assertEquals("/api/v2/logistics/create_shipping_document", sent.uri().getPath());
        assertTrue(sent.body().contains("\"order_sn\":\"241027SP1042\""));
    }

    @Test
    @DisplayName("getShippingDocumentStatus parses READY status")
    void getShippingDocumentStatus_Success() {
        String status = client.getShippingDocumentStatus(88L, "241027SP1042");

        assertEquals("READY", status);
    }

    @Test
    @DisplayName("downloadShippingDocument returns raw binary PDF bytes")
    void downloadShippingDocument_Success() {
        byte[] pdfBytes = client.downloadShippingDocument(88L, "241027SP1042", null);

        assertNotNull(pdfBytes);
        String text = new String(pdfBytes, StandardCharsets.ISO_8859_1);
        assertTrue(text.startsWith("%PDF-1.4"));
    }
}
