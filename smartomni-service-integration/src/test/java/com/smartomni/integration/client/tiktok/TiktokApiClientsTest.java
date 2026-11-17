package com.smartomni.integration.client.tiktok;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartomni.common.exception.BusinessException;
import com.smartomni.integration.dto.tiktok.CancellationSearchRequest;
import com.smartomni.integration.dto.tiktok.CancellationSearchResponse;
import com.smartomni.integration.dto.tiktok.OrderSearchRequest;
import com.smartomni.integration.dto.tiktok.OrderSearchResponse;
import com.smartomni.integration.dto.tiktok.ReturnSearchRequest;
import com.smartomni.integration.dto.tiktok.ReturnSearchResponse;
import com.smartomni.integration.dto.tiktok.ShipPackageRequest;
import com.smartomni.integration.dto.tiktok.ShippingDocumentResponse;
import com.smartomni.integration.dto.tiktok.TiktokOrderDetailResponse;
import com.smartomni.integration.dto.tiktok.TiktokPackageResponse;
import com.smartomni.integration.dto.tiktok.TiktokShippingProviderDto;
import com.smartomni.integration.service.tiktok.TiktokShopApiExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TikTok API Clients Unit Tests (Simulator develop contract)")
class TiktokApiClientsTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<String> recordedCalls = new ArrayList<>();

    private TiktokShopApiExecutor mockExecutor;
    private TiktokOrderClient orderClient;
    private TiktokFulfillmentClient fulfillmentClient;
    private TiktokCancellationClient cancellationClient;
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
                              "shipping_provider_name": "Simulator Express (fake carrier)",
                              "status": "SHIPPED"
                            }
                            """);
                    }
                    if (path.contains("/fulfillment/202309/packages/") && path.endsWith("/shipping_documents")) {
                        return mapper.readTree("""
                            {
                              "doc_url": "https://example.com/waybill.pdf",
                              "tracking_number": "TRACK-12345"
                            }
                            """);
                    }
                    if (path.endsWith("/fulfillment/202309/packages/pkg-001")) {
                        return mapper.readTree("""
                            {
                              "package_id": "pkg-001",
                              "shipping_provider_id": "SIM-CARRIER-1",
                              "shipping_provider_name": "Simulator Express (fake carrier)",
                              "handover_method": "PICKUP"
                            }
                            """);
                    }
                    if (path.contains("/return_refund/202309/cancellations/search")) {
                        return mapper.readTree("""
                            {
                              "next_page_token": "",
                              "total_count": 1,
                              "cancellations": [
                                {
                                  "cancel_id": "cancel-001",
                                  "order_id": "576000000000000001",
                                  "cancel_status": "CANCELLATION_REQUEST_PENDING"
                                }
                              ]
                            }
                            """);
                    }
                    if (path.contains("/return_refund/202309/returns/search")) {
                        return mapper.readTree("""
                            {
                              "next_page_token": "",
                              "total_count": 1,
                              "returns": [
                                {
                                  "return_id": "ret-001",
                                  "order_id": "576000000000000001",
                                  "return_status": "RETURN_OR_REFUND_REQUEST_PENDING"
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
        fulfillmentClient = new TiktokFulfillmentClient(mockExecutor, mapper, orderClient);
        cancellationClient = new TiktokCancellationClient(mockExecutor, mapper);
        returnClient = new TiktokReturnClient(mockExecutor, mapper);
    }

    @Test
    @DisplayName("TiktokOrderClient: searchOrders gọi POST /order/202309/orders/search với create_time_ge")
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

        assertTrue(recordedCalls.get(0).contains("callByShopCipher:TEST_CIPHER_001 POST /order/202309/orders/search"));
        assertTrue(recordedCalls.get(0).contains("\"create_time_ge\":1700000000"));
        assertFalse(recordedCalls.get(0).contains("\"order_id\""));
    }

    @Test
    @DisplayName("TiktokOrderClient: getOrderDetails gọi GET /order/202507/orders?ids=...")
    void testGetOrderDetails() {
        List<TiktokOrderDetailResponse> orders = orderClient.getOrderDetails("TEST_CIPHER_001",
                List.of("576000000000000001", "576000000000000002"));

        assertNotNull(orders);
        assertEquals(1, orders.size());
        assertEquals("576000000000000001", orders.get(0).getOrderId());

        assertTrue(recordedCalls.get(0).contains("callByShopCipher:TEST_CIPHER_001 GET /order/202507/orders"));
        assertTrue(recordedCalls.get(0).contains("ids=576000000000000001,576000000000000002"));
    }

    @Test
    @DisplayName("TiktokFulfillmentClient: shipPackage gọi POST /fulfillment/202309/packages/{package_id}/ship chỉ với handover_method")
    void testShipPackage() {
        ShipPackageRequest request = ShipPackageRequest.builder()
                .handoverMethod("PICKUP")
                .shippingServiceId("SVC-IGNORED")
                .build();

        TiktokPackageResponse response = fulfillmentClient.shipPackage("TEST_CIPHER_001", "pkg-001", request);

        assertNotNull(response);
        assertEquals("pkg-001", response.getPackageId());
        assertEquals("TRACK-12345", response.getTrackingNumber());
        assertEquals("SHIPPED", response.getStatus());

        assertTrue(recordedCalls.get(0).contains("POST /fulfillment/202309/packages/pkg-001/ship"));
        assertTrue(recordedCalls.get(0).contains("\"handover_method\":\"PICKUP\""));
        assertFalse(recordedCalls.get(0).contains("shipping_service_id"));
    }

    @Test
    @DisplayName("TiktokFulfillmentClient: getShippingDocuments chuẩn hóa SL_PDF -> SHIPPING_LABEL")
    void testGetShippingDocuments() {
        ShippingDocumentResponse response = fulfillmentClient.getShippingDocuments("TEST_CIPHER_001", "pkg-001", "SL_PDF");

        assertNotNull(response);
        assertEquals("https://example.com/waybill.pdf", response.getDocUrl());

        assertTrue(recordedCalls.get(0).contains("GET /fulfillment/202309/packages/pkg-001/shipping_documents"));
        assertTrue(recordedCalls.get(0).contains("document_type=SHIPPING_LABEL"));
    }

    @Test
    @DisplayName("TiktokFulfillmentClient: getShippingProviders đọc package detail hoặc trả SIM-CARRIER-1")
    void testGetShippingProviders() {
        List<TiktokShippingProviderDto> defaultProviders = fulfillmentClient.getShippingProviders(77L);
        assertEquals(2, defaultProviders.size());
        assertEquals("SIM-CARRIER-1", defaultProviders.get(0).getShippingProviderId());

        List<TiktokShippingProviderDto> pkgProviders = fulfillmentClient.getShippingProviders(77L, "pkg-001");
        assertEquals(1, pkgProviders.size());
        assertEquals("SIM-CARRIER-1", pkgProviders.get(0).getShippingProviderId());
        assertTrue(recordedCalls.get(0).contains("GET /fulfillment/202309/packages/pkg-001"));
    }

    @Test
    @DisplayName("TiktokFulfillmentClient: downloadShippingLabelByPackage ném BusinessException khi Simulator lỗi")
    void testDownloadShippingLabel_ApiError() {
        TiktokShopApiExecutor failingExecutor = new TiktokShopApiExecutor(null, null, null) {
            @Override
            public JsonNode call(Long connectionId, String method, String path, Map<String, String> query, String body) {
                throw new TiktokApiException("package does not exist in this shop", 404, 404, null, false);
            }
        };
        TiktokFulfillmentClient failingClient = new TiktokFulfillmentClient(failingExecutor, mapper, orderClient);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> failingClient.downloadShippingLabelByPackage(77L, "pkg-404"));
        assertEquals(HttpStatus.NOT_FOUND, ex.getHttpStatus());
        assertEquals("SHIPPING_LABEL_UNAVAILABLE", ex.getErrorCode());
    }

    @Test
    @DisplayName("TiktokCancellationClient: searchCancellations gọi POST /return_refund/202309/cancellations/search với order_ids")
    void testSearchCancellations() {
        CancellationSearchResponse response = cancellationClient.searchCancellations(77L,
                CancellationSearchRequest.builder().orderId("576000000000000001").build());

        assertNotNull(response);
        assertEquals(1, response.getCancellations().size());
        assertEquals("cancel-001", response.getCancellations().get(0).getCancelId());
        assertTrue(recordedCalls.get(0).contains("POST /return_refund/202309/cancellations/search"));
        assertTrue(recordedCalls.get(0).contains("\"order_ids\":[\"576000000000000001\"]"));
    }

    @Test
    @DisplayName("TiktokReturnClient: searchReturns gọi POST /return_refund/202309/returns/search với page_size >= 10 và order_ids")
    void testSearchReturns() {
        ReturnSearchRequest request = ReturnSearchRequest.builder()
                .pageSize(5)
                .orderId("576000000000000001")
                .returnStatus("RETURN_OR_REFUND_REQUEST_PENDING")
                .build();

        ReturnSearchResponse response = returnClient.searchReturns("TEST_CIPHER_001", request);

        assertNotNull(response);
        assertEquals(1, response.getReturns().size());
        assertEquals("ret-001", response.getReturns().get(0).getReturnId());

        assertTrue(recordedCalls.get(0).contains("POST /return_refund/202309/returns/search"));
        assertTrue(recordedCalls.get(0).contains("page_size=10"));
        assertTrue(recordedCalls.get(0).contains("\"order_ids\":[\"576000000000000001\"]"));
    }
}
