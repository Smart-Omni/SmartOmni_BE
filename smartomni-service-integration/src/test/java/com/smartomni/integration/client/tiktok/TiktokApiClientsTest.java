package com.smartomni.integration.client.tiktok;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartomni.integration.dto.tiktok.OrderSearchRequest;
import com.smartomni.integration.dto.tiktok.OrderSearchResponse;
import com.smartomni.integration.dto.tiktok.ReturnSearchRequest;
import com.smartomni.integration.dto.tiktok.ReturnSearchResponse;
import com.smartomni.integration.dto.tiktok.ShipPackageRequest;
import com.smartomni.integration.dto.tiktok.ShippingDocumentResponse;
import com.smartomni.integration.dto.tiktok.TiktokOrderDetailResponse;
import com.smartomni.integration.dto.tiktok.TiktokPackageResponse;
import com.smartomni.integration.service.tiktok.TiktokShopApiExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TikTok API Clients Unit Tests")
class TiktokApiClientsTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<String> recordedCalls = new ArrayList<>();

    private TiktokShopApiExecutor mockExecutor;
    private TiktokOrderClient orderClient;
    private TiktokFulfillmentClient fulfillmentClient;
    private TiktokReturnClient returnClient;

    @BeforeEach
    void setUp() {
        recordedCalls.clear();
        mockExecutor = new TiktokShopApiExecutor(null, null, null) {
            @Override
            public JsonNode call(Long connectionId, String method, String path, Map<String, String> query, String body) {
                recordedCalls.add("call:" + method + " " + path + " query=" + query + " body=" + body);
                return handleResponse(path);
            }

            @Override
            public JsonNode callByShopCipher(String shopCipher, String method, String path, Map<String, String> query, String body) {
                recordedCalls.add("callByShopCipher:" + shopCipher + " " + method + " " + path + " query=" + query + " body=" + body);
                return handleResponse(path);
            }

            private JsonNode handleResponse(String path) {
                try {
                    if (path.contains("/order/202309/orders/search")) {
                        return mapper.readTree("""
                            {
                              "next_page_token": "next_token_123",
                              "total_count": 1,
                              "orders": [
                                {
                                  "order_id": "576000000000000001",
                                  "order_status": "AWAITING_SHIPMENT"
                                }
                              ]
                            }
                            """);
                    }
                    if (path.contains("/order/202507/orders")) {
                        return mapper.readTree("""
                            {
                              "orders": [
                                {
                                  "order_id": "576000000000000001",
                                  "order_status": "AWAITING_SHIPMENT",
                                  "package_id": "pkg-001"
                                }
                              ]
                            }
                            """);
                    }
                    if (path.contains("/fulfillment/202309/packages/") && path.endsWith("/ship")) {
                        return mapper.readTree("""
                            {
                              "package_id": "pkg-001",
                              "tracking_number": "TRACK-12345",
                              "shipping_provider_name": "J&T Express",
                              "status": "SHIPPED"
                            }
                            """);
                    }
                    if (path.contains("/fulfillment/202309/packages/") && path.endsWith("/shipping_documents")) {
                        return mapper.readTree("""
                            {
                              "doc_url": "https://example.com/waybill.pdf",
                              "document_type": "SL_PDF",
                              "tracking_number": "TRACK-12345"
                            }
                            """);
                    }
                    if (path.contains("/return/202309/returns/search")) {
                        return mapper.readTree("""
                            {
                              "next_page_token": "",
                              "total_count": 1,
                              "returns": [
                                {
                                  "return_id": "ret-001",
                                  "order_id": "576000000000000001",
                                  "return_status": "RETURN_REQUESTED"
                                }
                              ]
                            }
                            """);
                    }
                    return mapper.createObjectNode();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        };

        orderClient = new TiktokOrderClient(mockExecutor, mapper);
        fulfillmentClient = new TiktokFulfillmentClient(mockExecutor, mapper);
        returnClient = new TiktokReturnClient(mockExecutor, mapper);
    }

    @Test
    @DisplayName("TiktokOrderClient: searchOrders gọi GET /order/202309/orders/search với đầy đủ tham số")
    void testSearchOrders() {
        OrderSearchRequest request = OrderSearchRequest.builder()
                .pageSize(20)
                .pageToken("token_abc")
                .orderStatus("AWAITING_SHIPMENT")
                .createTimeFrom(1700000000L)
                .build();

        OrderSearchResponse response = orderClient.searchOrders("TEST_CIPHER_001", request);

        assertNotNull(response);
        assertEquals("next_token_123", response.getNextPageToken());
        assertEquals(1, response.getOrders().size());
        assertEquals("576000000000000001", response.getOrders().get(0).getOrderId());

        assertTrue(recordedCalls.get(0).contains("callByShopCipher:TEST_CIPHER_001 GET /order/202309/orders/search"));
        assertTrue(recordedCalls.get(0).contains("order_status=AWAITING_SHIPMENT"));
    }

    @Test
    @DisplayName("TiktokOrderClient: getOrderDetails gọi GET /order/202507/orders với ids phân tách bởi dấu phẩy")
    void testGetOrderDetails() {
        List<TiktokOrderDetailResponse> orders = orderClient.getOrderDetails("TEST_CIPHER_001", List.of("576000000000000001", "576000000000000002"));

        assertNotNull(orders);
        assertEquals(1, orders.size());
        assertEquals("576000000000000001", orders.get(0).getOrderId());

        assertTrue(recordedCalls.get(0).contains("callByShopCipher:TEST_CIPHER_001 GET /order/202507/orders"));
        assertTrue(recordedCalls.get(0).contains("ids=576000000000000001,576000000000000002"));
    }

    @Test
    @DisplayName("TiktokFulfillmentClient: shipPackage gọi POST /fulfillment/202309/packages/{package_id}/ship")
    void testShipPackage() {
        ShipPackageRequest request = ShipPackageRequest.builder()
                .selfShipment(ShipPackageRequest.SelfShipment.builder()
                        .trackingNumber("TRACK-12345")
                        .shippingProviderId("SP-001")
                        .build())
                .build();

        TiktokPackageResponse response = fulfillmentClient.shipPackage("TEST_CIPHER_001", "pkg-001", request);

        assertNotNull(response);
        assertEquals("pkg-001", response.getPackageId());
        assertEquals("TRACK-12345", response.getTrackingNumber());
        assertEquals("SHIPPED", response.getStatus());

        assertTrue(recordedCalls.get(0).contains("callByShopCipher:TEST_CIPHER_001 POST /fulfillment/202309/packages/pkg-001/ship"));
    }

    @Test
    @DisplayName("TiktokFulfillmentClient: getShippingDocuments gọi GET /fulfillment/202309/packages/{package_id}/shipping_documents")
    void testGetShippingDocuments() {
        ShippingDocumentResponse response = fulfillmentClient.getShippingDocuments("TEST_CIPHER_001", "pkg-001", "SL_PDF");

        assertNotNull(response);
        assertEquals("https://example.com/waybill.pdf", response.getDocUrl());
        assertEquals("SL_PDF", response.getDocumentType());

        assertTrue(recordedCalls.get(0).contains("callByShopCipher:TEST_CIPHER_001 GET /fulfillment/202309/packages/pkg-001/shipping_documents"));
        assertTrue(recordedCalls.get(0).contains("document_type=SL_PDF"));
    }

    @Test
    @DisplayName("TiktokReturnClient: searchReturns gọi POST /return/202309/returns/search")
    void testSearchReturns() {
        ReturnSearchRequest request = ReturnSearchRequest.builder()
                .pageSize(20)
                .returnStatus("RETURN_REQUESTED")
                .build();

        ReturnSearchResponse response = returnClient.searchReturns("TEST_CIPHER_001", request);

        assertNotNull(response);
        assertEquals(1, response.getReturns().size());
        assertEquals("ret-001", response.getReturns().get(0).getReturnId());

        assertTrue(recordedCalls.get(0).contains("callByShopCipher:TEST_CIPHER_001 POST /return/202309/returns/search"));
    }
}
