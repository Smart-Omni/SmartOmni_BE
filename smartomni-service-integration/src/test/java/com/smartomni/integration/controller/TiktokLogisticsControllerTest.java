package com.smartomni.integration.controller;

import com.smartomni.common.dto.ApiResponse;
import com.smartomni.integration.client.tiktok.TiktokFulfillmentClient;
import com.smartomni.integration.controller.TiktokLogisticsController.TiktokShipPackageRequest;
import com.smartomni.integration.dto.tiktok.ShipPackageRequest;
import com.smartomni.integration.dto.tiktok.ShippingDocumentResponse;
import com.smartomni.integration.dto.tiktok.TiktokPackageResponse;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.service.catalog.TiktokTenantConnections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("TiktokLogisticsController Unit Tests")
class TiktokLogisticsControllerTest {

    private static final String CONTENT_DISPOSITION =
            org.springframework.http.HttpHeaders.CONTENT_DISPOSITION;

    private TiktokTenantConnections tenantConnections;
    private TiktokFulfillmentClient fulfillmentClient;
    private TiktokLogisticsController controller;
    private MarketplaceConnection connection;

    @BeforeEach
    void setUp() {
        tenantConnections = mock(TiktokTenantConnections.class);
        fulfillmentClient = mock(TiktokFulfillmentClient.class);

        controller = new TiktokLogisticsController(tenantConnections, fulfillmentClient);

        connection = new MarketplaceConnection();
        connection.setId(77L);
        connection.setTenantId(1L);
        connection.setPlatform(MarketplaceConnection.Platform.TIKTOK_SHOP);
        connection.setStatus(MarketplaceConnection.ConnectionStatus.CONNECTED);

        when(tenantConnections.requireConnected()).thenReturn(connection);
    }

    @Test
    @DisplayName("getShippingProviders returns connection info and supported document types")
    void getShippingProviders_Success() {
        ResponseEntity<ApiResponse<Map<String, Object>>> res =
                controller.getShippingProviders("SHOP-001");

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertNotNull(res.getBody());

        Map<String, Object> data = res.getBody().getData();
        assertNotNull(data);
        assertEquals(77L, data.get("connectionId"));
        assertEquals("SHOP-001", data.get("shopId"));
        assertInstanceOf(List.class, data.get("documentTypes"));
        assertTrue(((List<?>) data.get("documentTypes")).contains("SL_PDF"));
    }

    @Test
    @DisplayName("getShippingProviders works without shopId param")
    void getShippingProviders_NoShopId_Success() {
        ResponseEntity<ApiResponse<Map<String, Object>>> res =
                controller.getShippingProviders(null);

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertNotNull(res.getBody());
        assertNull(res.getBody().getData().get("shopId"));
    }

    @Test
    @DisplayName("shipPackage returns confirmed TiktokPackageResponse")
    void shipPackage_Success() {
        TiktokPackageResponse packageResponse = TiktokPackageResponse.builder()
                .packageId("PKG-001")
                .trackingNumber("TK-TRACK-001")
                .status("SHIPPED")
                .shippingProviderName("J&T Express")
                .build();

        when(fulfillmentClient.shipPackage(eq(77L), eq("PKG-001"), any(ShipPackageRequest.class)))
                .thenReturn(packageResponse);

        TiktokShipPackageRequest request = new TiktokShipPackageRequest();
        request.setShopId("SHOP-001");
        request.setOrderId("ORDER-001");
        request.setPackageId("PKG-001");
        request.setShippingServiceId("SVC-001");

        ResponseEntity<ApiResponse<TiktokPackageResponse>> res = controller.shipPackage(request);

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertNotNull(res.getBody());
        assertEquals("Package shipped successfully", res.getBody().getMessage());
        assertEquals("PKG-001", res.getBody().getData().getPackageId());
        assertEquals("TK-TRACK-001", res.getBody().getData().getTrackingNumber());
    }

    @Test
    @DisplayName("downloadShippingLabel streams PDF bytes with correct Content-Type and Content-Disposition headers")
    void downloadShippingLabel_Success() {
        // Use null docUrl / fileUrl so fetchPdfBytes returns an empty byte array (avoids real HTTP call)
        ShippingDocumentResponse nullUrlDoc = ShippingDocumentResponse.builder()
                .docUrl(null)
                .fileUrl(null)
                .documentType("SL_PDF")
                .build();

        when(fulfillmentClient.getShippingDocuments(eq(77L), eq("PKG-EMPTY"), eq("SL_PDF")))
                .thenReturn(nullUrlDoc);

        ResponseEntity<byte[]> res = controller.downloadShippingLabel(null, null, "PKG-EMPTY", "SL_PDF");

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertEquals(MediaType.APPLICATION_PDF, res.getHeaders().getContentType());
        assertTrue(res.getHeaders().getFirst(CONTENT_DISPOSITION).contains("TikTok-Label-PKG-EMPTY.pdf"));
        assertNotNull(res.getBody());
    }

    @Test
    @DisplayName("downloadShippingLabel resolves packageId from orderId when packageId is null")
    void downloadShippingLabel_FallbackToOrderId_Success() {
        ShippingDocumentResponse nullUrlDoc = ShippingDocumentResponse.builder()
                .docUrl(null)
                .fileUrl(null)
                .documentType("SL_PDF")
                .build();

        when(fulfillmentClient.getShippingDocuments(eq(77L), eq("ORDER-FALLBACK"), eq("SL_PDF")))
                .thenReturn(nullUrlDoc);

        ResponseEntity<byte[]> res = controller.downloadShippingLabel(null, "ORDER-FALLBACK", null, "SL_PDF");

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertTrue(res.getHeaders().getFirst(CONTENT_DISPOSITION).contains("TikTok-Label-ORDER-FALLBACK.pdf"));
        verify(fulfillmentClient).getShippingDocuments(eq(77L), eq("ORDER-FALLBACK"), eq("SL_PDF"));
    }
}
