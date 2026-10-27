package com.smartomni.integration.service.tiktok;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.smartomni.common.dto.ApiResponse;
import com.smartomni.common.dto.UpdateOrderTrackingRequest;
import com.smartomni.common.exception.BusinessException;
import com.smartomni.integration.client.order.OrderServiceClient;
import com.smartomni.integration.client.tiktok.TiktokFulfillmentClient;
import com.smartomni.integration.dto.tiktok.ShipPackageRequest;
import com.smartomni.integration.dto.tiktok.ShippingDocumentResponse;
import com.smartomni.integration.dto.tiktok.TiktokPackageResponse;
import com.smartomni.integration.dto.tiktok.TiktokWebhookPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TiktokFulfillmentService Unit Tests")
class TiktokFulfillmentServiceTest {

    private static final Long CONNECTION_ID = 10L;
    private static final Long TENANT_ID = 100L;
    private static final String PACKAGE_ID = "pkg_tiktok_999";
    private static final String TRACKING_NUMBER = "TRK_VN_88888888";

    @Mock
    private TiktokFulfillmentClient fulfillmentClient;

    @Mock
    private OrderServiceClient orderServiceClient;

    private ObjectMapper objectMapper;

    private TiktokFulfillmentService fulfillmentService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        fulfillmentService = new TiktokFulfillmentService(fulfillmentClient, orderServiceClient, objectMapper);
    }

    @Test
    @DisplayName("arrangeShipment: gọi TiktokFulfillmentClient.shipPackage thành công")
    void testArrangeShipmentSuccess() {
        ShipPackageRequest request = ShipPackageRequest.builder()
                .pickupSlot(ShipPackageRequest.PickupSlot.builder()
                        .startTime(1700000000L)
                        .endTime(1700003600L)
                        .build())
                .build();

        TiktokPackageResponse mockResponse = TiktokPackageResponse.builder()
                .packageId(PACKAGE_ID)
                .trackingNumber(TRACKING_NUMBER)
                .status("SHIPPED")
                .shippingProviderName("J&T Express")
                .orderIdList(List.of("order_001"))
                .build();

        when(fulfillmentClient.shipPackage(eq(CONNECTION_ID), eq(PACKAGE_ID), eq(request)))
                .thenReturn(mockResponse);

        TiktokPackageResponse response = fulfillmentService.arrangeShipment(CONNECTION_ID, PACKAGE_ID, request);

        assertNotNull(response);
        assertEquals(PACKAGE_ID, response.getPackageId());
        assertEquals(TRACKING_NUMBER, response.getTrackingNumber());
        assertEquals("SHIPPED", response.getStatus());

        verify(fulfillmentClient, times(1)).shipPackage(CONNECTION_ID, PACKAGE_ID, request);
    }

    @Test
    @DisplayName("arrangeShipment: ném BusinessException khi tham số không hợp lệ")
    void testArrangeShipmentInvalidParams() {
        assertThrows(BusinessException.class, () ->
                fulfillmentService.arrangeShipment(null, PACKAGE_ID, new ShipPackageRequest()));

        assertThrows(BusinessException.class, () ->
                fulfillmentService.arrangeShipment(CONNECTION_ID, "", new ShipPackageRequest()));
    }

    @Test
    @DisplayName("getShippingLabel: gọi TiktokFulfillmentClient.getShippingDocuments thành công")
    void testGetShippingLabelSuccess() {
        ShippingDocumentResponse mockDoc = ShippingDocumentResponse.builder()
                .docUrl("https://tiktok-shop.simulator.com/documents/waybill_999.pdf")
                .documentType("SL_PDF")
                .trackingNumber(TRACKING_NUMBER)
                .build();

        when(fulfillmentClient.getShippingDocuments(eq(CONNECTION_ID), eq(PACKAGE_ID), eq("SL_PDF")))
                .thenReturn(mockDoc);

        ShippingDocumentResponse result = fulfillmentService.getShippingLabel(CONNECTION_ID, PACKAGE_ID);

        assertNotNull(result);
        assertEquals("https://tiktok-shop.simulator.com/documents/waybill_999.pdf", result.getDocUrl());
        assertEquals("SL_PDF", result.getDocumentType());
        assertEquals(TRACKING_NUMBER, result.getTrackingNumber());

        String labelUrl = fulfillmentService.getShippingLabelUrl(CONNECTION_ID, PACKAGE_ID);
        assertEquals("https://tiktok-shop.simulator.com/documents/waybill_999.pdf", labelUrl);

        verify(fulfillmentClient, times(2)).getShippingDocuments(CONNECTION_ID, PACKAGE_ID, "SL_PDF");
    }

    @Test
    @DisplayName("getShippingLabel: ném BusinessException khi tham số không hợp lệ")
    void testGetShippingLabelInvalidParams() {
        assertThrows(BusinessException.class, () ->
                fulfillmentService.getShippingLabel(0L, PACKAGE_ID));

        assertThrows(BusinessException.class, () ->
                fulfillmentService.getShippingLabel(CONNECTION_ID, "   "));
    }

    @Test
    @DisplayName("handlePackageStatusChange: bóc tách dữ liệu và cập nhật đúng thông tin vận đơn khi trạng thái IN_TRANSIT")
    void testHandlePackageStatusChangeInTransit() {
        ObjectNode data = objectMapper.createObjectNode();
        data.put("package_id", PACKAGE_ID);
        data.put("tracking_number", TRACKING_NUMBER);
        data.put("shipping_provider_name", "SPX Express");
        data.put("package_status", "IN_TRANSIT");
        data.put("update_time", 1710000000L);

        ArrayNode orderIdList = data.putArray("order_id_list");
        orderIdList.add("TT_ORDER_101");
        orderIdList.add("TT_ORDER_102");

        TiktokWebhookPayload webhookPayload = TiktokWebhookPayload.builder()
                .ttsNotificationId("notif_pkg_001")
                .type(3)
                .shopId("SHOP_CIPHER_1")
                .timestamp(1710000000L)
                .data(data)
                .build();

        when(orderServiceClient.updateTracking(any(UpdateOrderTrackingRequest.class)))
                .thenReturn(ApiResponse.success(null));

        UpdateOrderTrackingRequest request = fulfillmentService.handlePackageStatusChange(TENANT_ID, webhookPayload);

        assertNotNull(request);
        assertEquals(TENANT_ID, request.getTenantId());
        assertEquals("TIKTOK_SHOP", request.getPlatform());
        assertEquals(PACKAGE_ID, request.getPackageId());
        assertEquals(TRACKING_NUMBER, request.getTrackingNumber());
        assertEquals("SPX Express", request.getShippingProvider());
        assertEquals("IN_TRANSIT", request.getPackageStatus());
        assertEquals("TT_ORDER_101", request.getPlatformOrderId());
        assertEquals(2, request.getOrderIdList().size());
        assertNotNull(request.getCourierScannedAt());
        assertEquals(Instant.ofEpochSecond(1710000000L), request.getCourierScannedAt());

        ArgumentCaptor<UpdateOrderTrackingRequest> captor = ArgumentCaptor.forClass(UpdateOrderTrackingRequest.class);
        verify(orderServiceClient, times(1)).updateTracking(captor.capture());

        UpdateOrderTrackingRequest sent = captor.getValue();
        assertEquals(TRACKING_NUMBER, sent.getTrackingNumber());
        assertEquals("SPX Express", sent.getShippingProvider());
        assertEquals("IN_TRANSIT", sent.getPackageStatus());
    }

    @Test
    @DisplayName("handlePackageStatusChange: khi trạng thái PICKED_UP cũng ghi nhận courierScannedAt")
    void testHandlePackageStatusChangePickedUp() {
        ObjectNode data = objectMapper.createObjectNode();
        data.put("package_id", PACKAGE_ID);
        data.put("tracking_number", TRACKING_NUMBER);
        data.put("shipping_provider", "NinjaVan");
        data.put("status", "PICKED_UP");
        data.put("order_id", "TT_ORDER_202");
        data.put("update_time", 1710005000000L); // timestamp dạng millis

        UpdateOrderTrackingRequest request = fulfillmentService.handlePackageStatusChange(TENANT_ID, data);

        assertNotNull(request);
        assertEquals("NinjaVan", request.getShippingProvider());
        assertEquals("PICKED_UP", request.getPackageStatus());
        assertEquals("TT_ORDER_202", request.getPlatformOrderId());
        assertEquals(Instant.ofEpochMilli(1710005000000L), request.getCourierScannedAt());

        verify(orderServiceClient, times(1)).updateTracking(any(UpdateOrderTrackingRequest.class));
    }

    @Test
    @DisplayName("handlePackageStatusChange: khi trạng thái CREATED chưa bàn giao cho bưu tá thì courierScannedAt là null")
    void testHandlePackageStatusChangeBeforeHandover() {
        ObjectNode data = objectMapper.createObjectNode();
        data.put("package_id", PACKAGE_ID);
        data.put("tracking_number", TRACKING_NUMBER);
        data.put("package_status", "PACKED");
        data.put("order_id", "TT_ORDER_303");

        UpdateOrderTrackingRequest request = fulfillmentService.handlePackageStatusChange(TENANT_ID, data);

        assertNotNull(request);
        assertEquals("PACKED", request.getPackageStatus());
        assertNull(request.getCourierScannedAt());

        verify(orderServiceClient, times(1)).updateTracking(any(UpdateOrderTrackingRequest.class));
    }
}
