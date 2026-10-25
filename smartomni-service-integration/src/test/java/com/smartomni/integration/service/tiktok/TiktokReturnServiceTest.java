package com.smartomni.integration.service.tiktok;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.smartomni.common.dto.ApiResponse;
import com.smartomni.common.dto.ProcessReturnRequest;
import com.smartomni.integration.client.order.OrderServiceClient;
import com.smartomni.integration.client.tiktok.TiktokReturnClient;
import com.smartomni.integration.dto.tiktok.ReturnSearchRequest;
import com.smartomni.integration.dto.tiktok.ReturnSearchResponse;
import com.smartomni.integration.dto.tiktok.TiktokReturnResponse;
import com.smartomni.integration.dto.tiktok.TiktokWebhookPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TiktokReturnService Unit Tests")
class TiktokReturnServiceTest {

    private static final Long TENANT_ID = 1L;
    private static final Long CONNECTION_ID = 10L;
    private static final String RETURN_ID = "ret_tiktok_999";
    private static final String ORDER_ID = "order_tiktok_111";

    @Mock
    private TiktokReturnClient returnClient;

    @Mock
    private OrderServiceClient orderServiceClient;

    private ObjectMapper objectMapper;

    private TiktokReturnService returnService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        returnService = new TiktokReturnService(returnClient, orderServiceClient, objectMapper);
    }

    @Test
    @DisplayName("searchReturns: gọi TiktokReturnClient.searchReturns thành công")
    void testSearchReturnsSuccess() {
        ReturnSearchRequest request = ReturnSearchRequest.builder()
                .pageSize(10)
                .returnStatus("RETURN_REQUESTED")
                .build();

        ReturnSearchResponse mockResponse = ReturnSearchResponse.builder()
                .returns(List.of(TiktokReturnResponse.builder()
                        .returnId(RETURN_ID)
                        .orderId(ORDER_ID)
                        .returnStatus("RETURN_REQUESTED")
                        .build()))
                .build();

        when(returnClient.searchReturns(eq(CONNECTION_ID), eq(request))).thenReturn(mockResponse);

        ReturnSearchResponse response = returnService.searchReturns(CONNECTION_ID, request);

        assertNotNull(response);
        assertEquals(1, response.getReturns().size());
        assertEquals(RETURN_ID, response.getReturns().get(0).getReturnId());

        verify(returnClient, times(1)).searchReturns(CONNECTION_ID, request);
    }

    @Test
    @DisplayName("handleReturnWebhook: bóc tách dữ liệu và gọi OrderServiceClient với restockRequired = true khi trả hàng thành công")
    void testHandleReturnWebhookReturnAndRefund() {
        ObjectNode data = objectMapper.createObjectNode();
        data.put("return_id", RETURN_ID);
        data.put("order_id", ORDER_ID);
        data.put("return_type", "RETURN_AND_REFUND");
        data.put("return_status", "RETURN_COMPLETED");
        data.put("return_reason", "Sản phẩm bị lỗi kỹ thuật");
        data.put("refund_amount", "350000.00");

        TiktokWebhookPayload webhookPayload = TiktokWebhookPayload.builder()
                .ttsNotificationId("notif_ret_001")
                .type(4)
                .shopId("SHOP_CIPHER_1")
                .data(data)
                .build();

        when(orderServiceClient.processReturn(any(ProcessReturnRequest.class)))
                .thenReturn(ApiResponse.success(null));

        ProcessReturnRequest request = returnService.handleReturnWebhook(TENANT_ID, webhookPayload);

        assertNotNull(request);
        assertEquals(TENANT_ID, request.getTenantId());
        assertEquals("TIKTOK_SHOP", request.getPlatform());
        assertEquals(ORDER_ID, request.getOrderId());
        assertEquals(RETURN_ID, request.getReturnId());
        assertEquals("RETURN_AND_REFUND", request.getReturnType());
        assertEquals("RETURN_COMPLETED", request.getReturnStatus());
        assertEquals("Sản phẩm bị lỗi kỹ thuật", request.getReturnReason());
        assertEquals(new BigDecimal("350000.00"), request.getRefundAmount());
        assertTrue(request.getRestockRequired());

        ArgumentCaptor<ProcessReturnRequest> captor = ArgumentCaptor.forClass(ProcessReturnRequest.class);
        verify(orderServiceClient, times(1)).processReturn(captor.capture());

        ProcessReturnRequest sent = captor.getValue();
        assertEquals(RETURN_ID, sent.getReturnId());
        assertTrue(sent.getRestockRequired());
    }

    @Test
    @DisplayName("handleReturnStatusChange: hoàn tiền không trả hàng (REFUND_ONLY) thì restockRequired = false")
    void testHandleReturnStatusChangeRefundOnly() {
        ObjectNode data = objectMapper.createObjectNode();
        data.put("return_id", RETURN_ID);
        data.put("order_id", ORDER_ID);
        data.put("return_type", "REFUND_ONLY");
        data.put("return_status", "REFUND_COMPLETE");
        data.put("reason", "Khách không nhận hàng được do vận chuyển chậm");
        data.put("refund_amount", "120000.00");

        ProcessReturnRequest request = returnService.handleReturnStatusChange(TENANT_ID, data);

        assertNotNull(request);
        assertEquals("REFUND_ONLY", request.getReturnType());
        assertFalse(request.getRestockRequired());

        verify(orderServiceClient, times(1)).processReturn(any(ProcessReturnRequest.class));
    }

    @Test
    @DisplayName("handleReturnStatusChange: khi yêu cầu trả hàng mới tạo (RETURN_REQUESTED) chưa hoàn kho (restockRequired = false)")
    void testHandleReturnStatusChangePending() {
        ObjectNode data = objectMapper.createObjectNode();
        data.put("return_id", RETURN_ID);
        data.put("order_id", ORDER_ID);
        data.put("return_type", "RETURN_AND_REFUND");
        data.put("return_status", "RETURN_REQUESTED");
        data.put("return_reason_text", "Đổi ý không muốn mua nữa");

        ProcessReturnRequest request = returnService.handleReturnStatusChange(TENANT_ID, data);

        assertNotNull(request);
        assertEquals("RETURN_REQUESTED", request.getReturnStatus());
        assertFalse(request.getRestockRequired());

        verify(orderServiceClient, times(1)).processReturn(any(ProcessReturnRequest.class));
    }
}
