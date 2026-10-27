package com.smartomni.integration.service.logistics;

import com.smartomni.integration.client.shopee.ShopeeLogisticsClient;
import com.smartomni.integration.client.shopee.ShopeeLogisticsClient.DropoffBranch;
import com.smartomni.integration.client.shopee.ShopeeLogisticsClient.PickupAddress;
import com.smartomni.integration.client.shopee.ShopeeLogisticsClient.ShippingParameter;
import com.smartomni.integration.client.shopee.ShopeeLogisticsClient.TimeSlot;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.service.logistics.ShopeeLogisticsService.ShipConfirmResult;
import com.smartomni.integration.service.logistics.ShopeeLogisticsService.ShipOrderRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("ShopeeLogisticsService Unit Tests")
class ShopeeLogisticsServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-27T10:00:00Z");

    private ShopeeLogisticsClient client;
    private ShopeeLogisticsService service;
    private MarketplaceConnection connection;

    @BeforeEach
    void setUp() {
        client = mock(ShopeeLogisticsClient.class);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new ShopeeLogisticsService(client, clock);

        connection = new MarketplaceConnection();
        connection.setId(88L);
        connection.setTenantId(1L);
        connection.setPlatform(MarketplaceConnection.Platform.SHOPEE);
        connection.setStatus(MarketplaceConnection.ConnectionStatus.CONNECTED);
    }

    @Test
    @DisplayName("getShippingParameter forwards call to ShopeeLogisticsClient")
    void getShippingParameter_Success() {
        ShippingParameter param = new ShippingParameter(
                List.of("branch_id"),
                List.of("address_id"),
                List.of(new DropoffBranch(101L, "Addr 1", "Dist", "Hanoi", "Hanoi", "VN", "10000")),
                List.of(new PickupAddress(901L, "Addr 2", "Dist", "Hanoi", "Hanoi", "VN", "10000",
                        List.of(new TimeSlot("slot_1", 1714100000L, "09:00 - 12:00"))))
        );
        when(client.getShippingParameter(88L, "241027SP1042")).thenReturn(param);

        ShippingParameter result = service.getShippingParameter(connection, "241027SP1042");

        assertNotNull(result);
        assertEquals(1, result.dropoffBranches().size());
        assertEquals(1, result.pickupAddresses().size());
    }

    @Test
    @DisplayName("confirmShipment ships order, assigns tracking, triggers AWB, and computes 48h SLA")
    void confirmShipment_Success() {
        when(client.shipOrder(eq(88L), eq("241027SP1042"), any(), any(), any()))
                .thenReturn(new ShopeeLogisticsClient.ShipOrderResult(true, "241027SP1042"));
        when(client.getTrackingNumber(eq(88L), eq("241027SP1042"), any()))
                .thenReturn("SPXVN998877");
        when(client.createShippingDocument(eq(88L), eq("241027SP1042"), any()))
                .thenReturn(true);

        ShipOrderRequest req = ShipOrderRequest.builder()
                .shippingMode("pickup")
                .addressId(901L)
                .pickupTimeId("slot_1")
                .build();

        ShipConfirmResult result = service.confirmShipment(connection, "241027SP1042", req);

        assertEquals("241027SP1042", result.getOrderSn());
        assertEquals("PROCESSED", result.getStatus());
        assertEquals("SPXVN998877", result.getTrackingNumber());
        assertEquals(172800L, result.getRemainingSeconds());
        assertEquals(NOW.getEpochSecond() + 172800L, result.getShipByDate());

        verify(client).shipOrder(eq(88L), eq("241027SP1042"), isNull(), any(), isNull());
        verify(client).createShippingDocument(88L, "241027SP1042", null);
    }

    @Test
    @DisplayName("downloadAwb triggers createShippingDocument if status is not ready, then downloads PDF")
    void downloadAwb_Success() {
        when(client.getShippingDocumentStatus(88L, "241027SP1042")).thenReturn("NOT_CREATED");
        when(client.downloadShippingDocument(88L, "241027SP1042", null))
                .thenReturn("%PDF-1.4 sample binary".getBytes(StandardCharsets.ISO_8859_1));

        byte[] pdfBytes = service.downloadAwb(connection, "241027SP1042");

        assertNotNull(pdfBytes);
        verify(client).createShippingDocument(88L, "241027SP1042", null);
        verify(client).downloadShippingDocument(88L, "241027SP1042", null);
    }
}
