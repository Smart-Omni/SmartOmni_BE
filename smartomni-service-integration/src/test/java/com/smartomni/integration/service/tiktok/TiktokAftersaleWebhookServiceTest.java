package com.smartomni.integration.service.tiktok;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartomni.common.dto.ProcessReturnRequest;
import com.smartomni.common.exception.BusinessException;
import com.smartomni.integration.client.order.OrderServiceClient;
import com.smartomni.integration.client.tiktok.TiktokCancellationClient;
import com.smartomni.integration.client.tiktok.TiktokOrderClient;
import com.smartomni.integration.client.tiktok.TiktokReturnClient;
import com.smartomni.integration.dto.tiktok.TiktokCancellationResponse;
import com.smartomni.integration.dto.tiktok.TiktokOrderDetailResponse;
import com.smartomni.integration.dto.tiktok.TiktokRefundAmount;
import com.smartomni.integration.dto.tiktok.TiktokReturnResponse;
import com.smartomni.integration.entity.MarketplaceConnection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TiktokAftersaleWebhookServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final TiktokReturnClient returnClient = mock(TiktokReturnClient.class);
    private final TiktokCancellationClient cancellationClient = mock(TiktokCancellationClient.class);
    private final TiktokOrderClient orderClient = mock(TiktokOrderClient.class);
    private final OrderServiceClient orderService = mock(OrderServiceClient.class);
    private final TiktokReturnService returnService = new TiktokReturnService(returnClient, orderService, mapper, null);
    private final TiktokAftersaleWebhookService service =
            new TiktokAftersaleWebhookService(returnClient, cancellationClient, orderClient, returnService);
    private final MarketplaceConnection connection = new MarketplaceConnection();

    @BeforeEach
    void setUp() {
        connection.setId(10L);
        connection.setTenantId(5L);
    }

    private static TiktokReturnResponse.ReturnLineItem line(String id) {
        return TiktokReturnResponse.ReturnLineItem.builder().returnItemId("RL-" + id).orderLineItemId("OL-" + id)
                .skuId("SKU-" + id).refundAmount(new TiktokRefundAmount("VND", "100", "0", "0", "100")).build();
    }

    private TiktokReturnResponse current(String status, long updateTime, int lines) {
        List<TiktokReturnResponse.ReturnLineItem> items = new java.util.ArrayList<>();
        for (int i = 1; i <= lines; i++) items.add(line(String.valueOf(i)));
        return TiktokReturnResponse.builder().returnId("RET-1").orderId("ORD-1").returnStatus(status)
                .returnType("RETURN_AND_REFUND").updateTime(updateTime)
                .refundAmount(new TiktokRefundAmount("VND", "180", "20", "0", "200"))
                .returnLineItems(items).build();
    }

    private void orderHasLines(int lines) {
        List<TiktokOrderDetailResponse.LineItem> items = new java.util.ArrayList<>();
        for (int i = 0; i < lines; i++) items.add(TiktokOrderDetailResponse.LineItem.builder().id("OL-" + (i + 1)).build());
        when(orderClient.getOrderDetail(10L, "ORD-1"))
                .thenReturn(TiktokOrderDetailResponse.builder().orderId("ORD-1").lineItems(items).build());
    }

    @Test
    void returnEventForwardsTheCurrentRecordWithRefundTotal() throws Exception {
        when(returnClient.getReturnDetail(10L, "RET-1")).thenReturn(current("RETURN_OR_REFUND_REQUEST_SUCCESS", 200, 2));
        orderHasLines(2);

        service.handleReturnEvent(5L, connection, mapper.readTree("{\"return_id\":\"RET-1\",\"update_time\":200}"));

        ArgumentCaptor<ProcessReturnRequest> sent = ArgumentCaptor.forClass(ProcessReturnRequest.class);
        verify(orderService).processReturn(sent.capture());
        assertEquals(new BigDecimal("200"), sent.getValue().getRefundAmount());
        assertEquals("RETURN_OR_REFUND_REQUEST_SUCCESS", sent.getValue().getReturnStatus());
        assertEquals(10L, sent.getValue().getConnectionId());
        assertFalse(sent.getValue().getPartialReturn());
        assertEquals(2, sent.getValue().getItems().size());
        assertEquals(new BigDecimal("100"), sent.getValue().getItems().get(0).getRefundAmount());
    }

    @Test
    void staleEventAppliesTheCurrentStateNotTheEventStatus() throws Exception {
        when(returnClient.getReturnDetail(10L, "RET-1")).thenReturn(current("RETURN_OR_REFUND_REQUEST_COMPLETE", 300, 2));
        orderHasLines(2);

        service.handleReturnEvent(5L, connection, mapper.readTree(
                "{\"return_id\":\"RET-1\",\"return_status\":\"RETURN_OR_REFUND_REQUEST_PENDING\",\"update_time\":100}"));

        ArgumentCaptor<ProcessReturnRequest> sent = ArgumentCaptor.forClass(ProcessReturnRequest.class);
        verify(orderService).processReturn(sent.capture());
        assertEquals("RETURN_OR_REFUND_REQUEST_COMPLETE", sent.getValue().getReturnStatus());
    }

    @Test
    void returnOfSomeUnitsIsMarkedPartial() throws Exception {
        when(returnClient.getReturnDetail(10L, "RET-1")).thenReturn(current("RETURN_OR_REFUND_REQUEST_SUCCESS", 200, 1));
        orderHasLines(3);

        service.handleReturnEvent(5L, connection, mapper.readTree("{\"return_id\":\"RET-1\"}"));

        ArgumentCaptor<ProcessReturnRequest> sent = ArgumentCaptor.forClass(ProcessReturnRequest.class);
        verify(orderService).processReturn(sent.capture());
        assertTrue(sent.getValue().getPartialReturn());
    }

    @Test
    void missingRecordIsTransientAndOrderServiceFailurePropagates() throws Exception {
        when(returnClient.getReturnDetail(10L, "RET-9")).thenReturn(null);
        BusinessException notReady = assertThrows(BusinessException.class,
                () -> service.handleReturnEvent(5L, connection, mapper.readTree("{\"return_id\":\"RET-9\"}")));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, notReady.getHttpStatus());

        when(returnClient.getReturnDetail(10L, "RET-1")).thenReturn(current("RETURN_OR_REFUND_REQUEST_SUCCESS", 200, 0));
        doThrow(new RuntimeException("order service down")).when(orderService).processReturn(any());
        assertThrows(RuntimeException.class,
                () -> service.handleReturnEvent(5L, connection, mapper.readTree("{\"return_id\":\"RET-1\"}")));
    }

    @Test
    void eventWithoutIdIsInvalid() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.handleReturnEvent(5L, connection, mapper.readTree("{}")));
        assertEquals("INVALID_PAYLOAD", ex.getErrorCode());
    }

    @Test
    void cancellationEventReadsTheCurrentRecordAndDoesNotTouchTheReturnFlow() throws Exception {
        when(cancellationClient.getCancellation(10L, "CNL-1")).thenReturn(TiktokCancellationResponse.builder()
                .cancelId("CNL-1").orderId("ORD-2").cancelStatus("CANCELLATION_REQUEST_COMPLETE").updateTime(50L).build());

        TiktokCancellationResponse c = service.handleCancellationEvent(5L, connection,
                mapper.readTree("{\"cancel_id\":\"CNL-1\",\"update_time\":10}"));

        assertEquals("CANCELLATION_REQUEST_COMPLETE", c.getCancelStatus());
        verifyNoInteractions(orderService);
    }

    @Test
    void tiktokReturnNeverRequestsSmartOmniRestockDirectly() throws Exception {
        // Restock is decided by the order service; for TIKTOK_SHOP it records PLATFORM_SYNC and never calls inventory.
        when(returnClient.getReturnDetail(10L, "RET-1")).thenReturn(current("RETURN_OR_REFUND_REQUEST_COMPLETE", 200, 2));
        orderHasLines(2);

        service.handleReturnEvent(5L, connection, mapper.readTree("{\"return_id\":\"RET-1\"}"));

        ArgumentCaptor<ProcessReturnRequest> sent = ArgumentCaptor.forClass(ProcessReturnRequest.class);
        verify(orderService).processReturn(sent.capture());
        assertEquals("TIKTOK_SHOP", sent.getValue().getPlatform());
    }
}
