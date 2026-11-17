package com.smartomni.integration.controller;

import com.smartomni.common.dto.ApiResponse;
import com.smartomni.common.exception.BusinessException;
import com.smartomni.integration.client.tiktok.TiktokFulfillmentClient;
import com.smartomni.integration.controller.TiktokLogisticsController.TiktokShipPackageRequest;
import com.smartomni.integration.dto.tiktok.ShipPackageRequest;
import com.smartomni.integration.dto.tiktok.TiktokPackageResponse;
import com.smartomni.integration.dto.tiktok.TiktokShippingProviderDto;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.service.catalog.TiktokTenantConnections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("TiktokLogisticsController Unit Tests")
class TiktokLogisticsControllerTest {

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
    @DisplayName("getShippingProviders returns providers mapped from TikTok Simulator (D3)")
    void getShippingProviders_Success() {
        List<TiktokShippingProviderDto> providers = List.of(
                TiktokShippingProviderDto.builder()
                        .shippingProviderId("SP-001")
                        .shippingProviderName("J&T Express")
                        .shippingServiceId("SVC-001")
                        .shippingServiceName("Standard")
                        .build()
        );
        when(fulfillmentClient.getShippingProviders(77L, null)).thenReturn(providers);

        ResponseEntity<ApiResponse<List<TiktokShippingProviderDto>>> res =
                controller.getShippingProviders("SHOP-001", null);

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertNotNull(res.getBody());
        assertEquals(1, res.getBody().getData().size());
        assertEquals("SP-001", res.getBody().getData().get(0).getShippingProviderId());
        assertEquals("SVC-001", res.getBody().getData().get(0).getShippingServiceId());
    }

    @Test
    @DisplayName("shipPackage forwards handoverMethod and shippingServiceId to fulfillmentClient (D4)")
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
        request.setHandoverMethod("PICKUP");

        ResponseEntity<ApiResponse<TiktokPackageResponse>> res = controller.shipPackage(null, request);

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertNotNull(res.getBody());
        assertEquals("Package shipped successfully", res.getBody().getMessage());
        assertEquals("PKG-001", res.getBody().getData().getPackageId());

        ArgumentCaptor<ShipPackageRequest> captor = ArgumentCaptor.forClass(ShipPackageRequest.class);
        verify(fulfillmentClient).shipPackage(eq(77L), eq("PKG-001"), captor.capture());
        assertEquals("PICKUP", captor.getValue().getHandoverMethod());
        assertEquals("SVC-001", captor.getValue().getShippingServiceId());
    }

    @Test
    @DisplayName("downloadShippingLabel streams PDF bytes with Content-Type: application/pdf (D5)")
    void downloadShippingLabel_ByPackage_Success() {
        byte[] pdfBytes = "%PDF-1.4\n1 0 obj\n<<>>\nendobj".getBytes();
        when(fulfillmentClient.downloadShippingLabelByPackage(77L, "PKG-001", "SHIPPING_LABEL")).thenReturn(pdfBytes);

        ResponseEntity<byte[]> res = controller.downloadShippingLabel("PKG-001", null, null, null, null, "SHIPPING_LABEL");

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertEquals(MediaType.APPLICATION_PDF, res.getHeaders().getContentType());
        assertTrue(res.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION).contains("TikTok-Label-PKG-001.pdf"));
        assertArrayEquals(pdfBytes, res.getBody());
    }

    @Test
    @DisplayName("downloadShippingLabel downloads by orderId when packageId is absent (D5)")
    void downloadShippingLabel_ByOrder_Success() {
        byte[] pdfBytes = "%PDF-1.4\norder-label".getBytes();
        when(fulfillmentClient.downloadShippingLabelByOrder(77L, "ORD-001", "SHIPPING_LABEL")).thenReturn(pdfBytes);

        ResponseEntity<byte[]> res = controller.downloadShippingLabel(null, null, null, "ORD-001", null, "SHIPPING_LABEL");

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertEquals(MediaType.APPLICATION_PDF, res.getHeaders().getContentType());
        assertTrue(res.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION).contains("TikTok-Label-ORD-001.pdf"));
        verify(fulfillmentClient).downloadShippingLabelByOrder(77L, "ORD-001", "SHIPPING_LABEL");
    }

    @Test
    @DisplayName("downloadShippingLabel throws BusinessException SHIPPING_LABEL_UNAVAILABLE when IDs are missing (D5)")
    void downloadShippingLabel_MissingIds_ThrowsBusinessException() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.downloadShippingLabel(null, null, null, null, null, "SL_PDF"));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getHttpStatus());
        assertEquals("SHIPPING_LABEL_UNAVAILABLE", ex.getErrorCode());
    }
}
