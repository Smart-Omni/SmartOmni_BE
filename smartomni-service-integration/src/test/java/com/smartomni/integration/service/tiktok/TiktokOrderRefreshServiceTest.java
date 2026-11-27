package com.smartomni.integration.service.tiktok;

import com.smartomni.common.exception.BusinessException;
import com.smartomni.integration.client.tiktok.TiktokApiException;
import com.smartomni.integration.client.tiktok.TiktokOrderClient;
import com.smartomni.integration.dto.tiktok.TiktokOrderDetailResponse;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.service.catalog.TiktokTenantConnections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TiktokOrderRefreshServiceTest {

    private final TiktokTenantConnections connections = mock(TiktokTenantConnections.class);
    private final TiktokOrderClient orders = mock(TiktokOrderClient.class);
    private final TiktokTrackingEnricher enricher = mock(TiktokTrackingEnricher.class);
    private final TiktokOrderIngestionService ingestion = mock(TiktokOrderIngestionService.class);
    private final TiktokOrderRefreshService service = new TiktokOrderRefreshService(connections, orders, enricher, ingestion);
    private final MarketplaceConnection connection = new MarketplaceConnection();

    @BeforeEach
    void setUp() {
        connection.setId(7L);
        connection.setTenantId(1L);
        when(connections.connected(1L)).thenReturn(connection);
    }

    private static TiktokOrderDetailResponse order(String id, String status) {
        TiktokOrderDetailResponse order = new TiktokOrderDetailResponse();
        order.setOrderId(id);
        order.setOrderStatus(status);
        return order;
    }

    @Test
    void foundOrderGoesThroughNormalIngestion() {
        TiktokOrderDetailResponse completed = order("576", "COMPLETED");
        when(orders.getOrderDetail(7L, "576")).thenReturn(completed);

        assertThat(service.refresh(1L, " 576 ")).containsEntry("outcome", "FOUND").containsEntry("orderStatus", "COMPLETED");
        verify(enricher).enrich(connection, completed);
        verify(ingestion).ingestOrder(1L, completed, TiktokOrderIngestionService.SOURCE_POLLING);
    }

    @Test
    void unknownOrderIsNotFoundAndNothingIsIngested() {
        when(orders.getOrderDetail(7L, "999")).thenReturn(null);
        assertThat(service.refresh(1L, "999")).containsEntry("outcome", "NOT_FOUND");

        when(orders.getOrderDetail(7L, "998")).thenReturn(order("other", "COMPLETED"));
        assertThat(service.refresh(1L, "998")).containsEntry("outcome", "NOT_FOUND");

        when(orders.getOrderDetail(7L, "997")).thenThrow(new TiktokApiException("not found", 404, 404, "no order", false));
        assertThat(service.refresh(1L, "997")).containsEntry("outcome", "NOT_FOUND");
        verify(ingestion, never()).ingestOrder(anyLong(), any(TiktokOrderDetailResponse.class), anyString());
    }

    @Test
    void transientTiktokFailureIsUnavailable() {
        when(orders.getOrderDetail(eq(7L), anyString())).thenThrow(new TiktokApiException("timeout", 504, 504, "t", true));
        assertThat(service.refresh(1L, "576")).containsEntry("outcome", "UNAVAILABLE");
    }

    @Test
    void tenantWithoutTiktokIsNotConnected() {
        when(connections.connected(2L)).thenThrow(new BusinessException("no", HttpStatus.CONFLICT, "TIKTOK_NOT_CONNECTED"));
        assertThat(service.refresh(2L, "576")).containsEntry("outcome", "NOT_CONNECTED");
    }
}
