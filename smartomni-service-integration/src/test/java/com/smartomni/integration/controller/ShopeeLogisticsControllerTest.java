package com.smartomni.integration.controller;

import com.smartomni.common.dto.ApiResponse;
import com.smartomni.integration.client.shopee.ShopeeLogisticsClient;
import com.smartomni.integration.client.shopee.ShopeeLogisticsClient.ChannelDetail;
import com.smartomni.integration.client.shopee.ShopeeLogisticsClient.ShippingParameter;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.service.logistics.ShopeeLogisticsService;
import com.smartomni.integration.service.logistics.ShopeeLogisticsService.ShipConfirmResult;
import com.smartomni.integration.service.logistics.ShopeeLogisticsService.ShipOrderRequest;
import com.smartomni.integration.service.shopee.ShopeeTenantConnections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("ShopeeLogisticsController Unit Tests")
class ShopeeLogisticsControllerTest {

    private ShopeeTenantConnections tenantConnections;
    private ShopeeLogisticsClient logisticsClient;
    private ShopeeLogisticsService logisticsService;
    private ShopeeLogisticsController controller;
    private MarketplaceConnection connection;

    @BeforeEach
    void setUp() {
        tenantConnections = mock(ShopeeTenantConnections.class);
        logisticsClient = mock(ShopeeLogisticsClient.class);
        logisticsService = mock(ShopeeLogisticsService.class);

        controller = new ShopeeLogisticsController(tenantConnections, logisticsClient, logisticsService);

        connection = new MarketplaceConnection();
        connection.setId(88L);
        connection.setTenantId(1L);
        connection.setPlatform(MarketplaceConnection.Platform.SHOPEE);
        connection.setStatus(MarketplaceConnection.ConnectionStatus.CONNECTED);

        when(tenantConnections.requireConnected()).thenReturn(connection);
    }

    @Test
    @DisplayName("getChannelList returns supported shipping channels")
    void getChannelList_Success() {
        when(logisticsClient.getChannelList(88L)).thenReturn(List.of(
                new ChannelDetail(30001L, "SPX Express", true, true, "CUSTOM_PRICE")
        ));

        ResponseEntity<ApiResponse<List<ChannelDetail>>> res = controller.getChannelList();

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertNotNull(res.getBody());
        assertEquals(1, res.getBody().getData().size());
        assertEquals("SPX Express", res.getBody().getData().get(0).channelName());
    }

    @Test
    @DisplayName("getShippingParameter returns dropoff and pickup options")
    void getShippingParameter_Success() {
        ShippingParameter param = new ShippingParameter(List.of(), List.of(), List.of(), List.of());
        when(logisticsService.getShippingParameter(connection, "241027SP1042")).thenReturn(param);

        ResponseEntity<ApiResponse<ShippingParameter>> res = controller.getShippingParameter("241027SP1042");

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertNotNull(res.getBody());
    }

    @Test
    @DisplayName("shipOrder returns confirmed shipping details and tracking number")
    void shipOrder_Success() {
        ShipConfirmResult confirmResult = ShipConfirmResult.builder()
                .orderSn("241027SP1042")
                .status("PROCESSED")
                .trackingNumber("SPXVN998877")
                .shipByDate(1714173300L)
                .remainingSeconds(86400L)
                .build();

        ShipOrderRequest req = ShipOrderRequest.builder()
                .shippingMode("pickup")
                .addressId(901L)
                .pickupTimeId("slot_1")
                .build();

        when(logisticsService.confirmShipment(connection, "241027SP1042", req)).thenReturn(confirmResult);

        ResponseEntity<ApiResponse<ShipConfirmResult>> res = controller.shipOrder("241027SP1042", req);

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertNotNull(res.getBody());
        assertEquals("SPXVN998877", res.getBody().getData().getTrackingNumber());
    }

    @Test
    @DisplayName("downloadShippingDocument streams binary PDF with proper headers")
    void downloadShippingDocument_Success() {
        byte[] pdfBytes = "%PDF-1.4 test document".getBytes(StandardCharsets.ISO_8859_1);
        when(logisticsService.downloadAwb(connection, "241027SP1042")).thenReturn(pdfBytes);

        ResponseEntity<byte[]> res = controller.downloadShippingDocument("241027SP1042");

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertEquals(MediaType.APPLICATION_PDF, res.getHeaders().getContentType());
        assertTrue(res.getHeaders().getFirst("Content-Disposition").contains("AWB-241027SP1042.pdf"));
        assertArrayEquals(pdfBytes, res.getBody());
    }
}
