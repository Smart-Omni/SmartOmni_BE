package com.smartomni.integration.service.tiktok;

import com.smartomni.integration.client.tiktok.TiktokFulfillmentClient;
import com.smartomni.integration.dto.tiktok.TiktokOrderDetailResponse;
import com.smartomni.integration.dto.tiktok.TiktokPackageResponse;
import com.smartomni.integration.entity.MarketplaceConnection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("TiktokTrackingEnricher: polled orders get the tracking number from Get Package Detail")
class TiktokTrackingEnricherTest {

    private TiktokFulfillmentClient client;
    private TiktokTrackingEnricher enricher;
    private MarketplaceConnection connection;

    @BeforeEach
    void setUp() {
        client = mock(TiktokFulfillmentClient.class);
        enricher = new TiktokTrackingEnricher(client);
        connection = new MarketplaceConnection();
        connection.setId(1L);
        connection.setTenantId(10L);
        connection.setShopCipher("CIPHER");
    }

    private static TiktokOrderDetailResponse order(String status, String... packageIds) {
        return TiktokOrderDetailResponse.builder()
                .orderId("ORD-1")
                .orderStatus(status)
                .packageList(java.util.Arrays.stream(packageIds)
                        .map(id -> TiktokOrderDetailResponse.OrderPackage.builder().packageId(id).build())
                        .toList())
                .build();
    }

    private static TiktokPackageResponse detail(String tracking, String carrier) {
        return TiktokPackageResponse.builder().trackingNumber(tracking).shippingProviderName(carrier).build();
    }

    @Test
    void fillsTrackingAndCarrierOfAShippedOrder() {
        when(client.getPackageDetailByShopCipher("CIPHER", "PKG-1")).thenReturn(detail("SIM123", "Express"));
        TiktokOrderDetailResponse order = order("AWAITING_COLLECTION", "PKG-1");

        enricher.enrich(connection, order);

        assertEquals("SIM123", order.getTrackingNumber());
        assertEquals("Express", order.getShippingProvider());
    }

    @Test
    void usesTheConnectionIdWhenThereIsNoShopCipher() {
        connection.setShopCipher(null);
        when(client.getPackageDetail(1L, "PKG-1")).thenReturn(detail("SIM123", null));
        TiktokOrderDetailResponse order = order("DELIVERED", "PKG-1");

        enricher.enrich(connection, order);

        assertEquals("SIM123", order.getTrackingNumber());
        assertNull(order.getShippingProvider());
    }

    @Test
    void doesNotCallTheMarketplaceBeforeShipmentIsArranged() {
        for (String status : List.of("UNPAID", "AWAITING_SHIPMENT", "CANCELLED")) {
            enricher.enrich(connection, order(status, "PKG-1"));
        }

        verifyNoInteractions(client);
    }

    @Test
    void keepsATrackingNumberTheOrderAlreadyHas() {
        TiktokOrderDetailResponse order = order("IN_TRANSIT", "PKG-1");
        order.setTrackingNumber("KNOWN");

        enricher.enrich(connection, order);

        assertEquals("KNOWN", order.getTrackingNumber());
        verifyNoInteractions(client);
    }

    @Test
    void aFailingPackageDoesNotStopTheOrderAndTheNextOneIsTried() {
        when(client.getPackageDetailByShopCipher("CIPHER", "PKG-1")).thenThrow(new RuntimeException("timeout"));
        when(client.getPackageDetailByShopCipher("CIPHER", "PKG-2")).thenReturn(detail("SIM456", "Express"));
        TiktokOrderDetailResponse order = order("PARTIALLY_SHIPPING", "PKG-1", "PKG-2");

        assertDoesNotThrow(() -> enricher.enrich(connection, order));

        assertEquals("SIM456", order.getTrackingNumber());
    }
}
