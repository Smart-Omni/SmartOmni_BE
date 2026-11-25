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

    private ObjectNode packageEvent(String status) {
        ObjectNode data = objectMapper.createObjectNode();
        data.put("order_id", "TT_ORDER_101");
        data.put("package_id", PACKAGE_ID);
        data.put("package_status", status);
        data.put("update_time", 1710000000L);
        return data;
    }

    @Test
    @DisplayName("handlePackageStatusChange: webhook không có mã vận đơn thì lấy từ Get Package Detail")
    void packageUpdateReadsTrackingFromPackageDetail() {
        when(fulfillmentClient.getPackageDetail(CONNECTION_ID, PACKAGE_ID)).thenReturn(TiktokPackageResponse.builder()
                .packageId(PACKAGE_ID).trackingNumber(TRACKING_NUMBER).shippingProviderName("Sim Express")
                .packageStatus("PROCESSING").build());

        UpdateOrderTrackingRequest request =
                fulfillmentService.handlePackageStatusChange(TENANT_ID, CONNECTION_ID, packageEvent("PROCESSING"));

        assertEquals(TENANT_ID, request.getTenantId());
        assertEquals("TIKTOK_SHOP", request.getPlatform());
        assertEquals("TT_ORDER_101", request.getPlatformOrderId());
        assertEquals(List.of("TT_ORDER_101"), request.getOrderIdList());
        assertEquals(PACKAGE_ID, request.getPackageId());
        assertEquals(TRACKING_NUMBER, request.getTrackingNumber());
        assertEquals("Sim Express", request.getShippingProvider());
        assertEquals("PROCESSING", request.getPackageStatus());
        assertNull(request.getCourierScannedAt(), "PROCESSING is awaiting collection, not yet with the carrier");

        ArgumentCaptor<UpdateOrderTrackingRequest> sent = ArgumentCaptor.forClass(UpdateOrderTrackingRequest.class);
        verify(orderServiceClient).updateTracking(sent.capture());
        assertEquals(TRACKING_NUMBER, sent.getValue().getTrackingNumber());
    }

    @Test
    @DisplayName("handlePackageStatusChange: FULFILLING là đã bàn giao cho bưu tá, courierScannedAt = update_time")
    void fulfillingSetsCourierScanFromEventTime() {
        when(fulfillmentClient.getPackageDetail(CONNECTION_ID, PACKAGE_ID)).thenReturn(TiktokPackageResponse.builder()
                .trackingNumber(TRACKING_NUMBER).shippingProviderName("Sim Express").build());

        UpdateOrderTrackingRequest request =
                fulfillmentService.handlePackageStatusChange(TENANT_ID, CONNECTION_ID, packageEvent("FULFILLING"));

        assertEquals(Instant.ofEpochSecond(1710000000L), request.getCourierScannedAt());
    }

    @Test
    @DisplayName("handlePackageStatusChange: webhook đã có đủ mã vận đơn và hãng thì không gọi Get Package Detail")
    void fullPayloadSkipsPackageDetail() {
        ObjectNode data = packageEvent("IN_TRANSIT");
        data.put("tracking_number", TRACKING_NUMBER);
        data.put("shipping_provider", "NinjaVan");
        data.put("update_time", 1710005000000L); // millis
        ArrayNode ids = data.putArray("order_id_list");
        ids.add("TT_ORDER_101");
        ids.add("TT_ORDER_102");

        UpdateOrderTrackingRequest request = fulfillmentService.handlePackageStatusChange(TENANT_ID, CONNECTION_ID, data);

        verify(fulfillmentClient, never()).getPackageDetail(any(), any());
        assertEquals("NinjaVan", request.getShippingProvider());
        assertEquals(2, request.getOrderIdList().size());
        assertEquals(Instant.ofEpochMilli(1710005000000L), request.getCourierScannedAt());
    }

    @Test
    @DisplayName("handlePackageStatusChange: lỗi order service không bị nuốt, để webhook trả 503 và TikTok gửi lại")
    void orderServiceFailurePropagates() {
        when(fulfillmentClient.getPackageDetail(CONNECTION_ID, PACKAGE_ID)).thenReturn(TiktokPackageResponse.builder()
                .trackingNumber(TRACKING_NUMBER).build());
        when(orderServiceClient.updateTracking(any(UpdateOrderTrackingRequest.class)))
                .thenThrow(new IllegalStateException("order service down"));

        assertThrows(IllegalStateException.class,
                () -> fulfillmentService.handlePackageStatusChange(TENANT_ID, CONNECTION_ID, packageEvent("PROCESSING")));
    }

    @Test
    @DisplayName("handlePackageStatusChange: không có order_id thì bỏ qua, không gọi order service")
    void eventWithoutOrderIsSkipped() {
        ObjectNode data = objectMapper.createObjectNode();
        data.put("package_id", PACKAGE_ID);
        when(fulfillmentClient.getPackageDetail(CONNECTION_ID, PACKAGE_ID)).thenReturn(TiktokPackageResponse.builder().build());

        assertNull(fulfillmentService.handlePackageStatusChange(TENANT_ID, CONNECTION_ID, data));
        verify(orderServiceClient, never()).updateTracking(any());
    }

    @Test
    @DisplayName("isCarrierHandover: chỉ FULFILLING và các giá trị cũ IN_TRANSIT/PICKED_UP/SHIPPED")
    void carrierHandoverStatuses() {
        for (String yes : List.of("FULFILLING", "in_transit", "PICKED_UP", "SHIPPED")) {
            assertTrue(TiktokFulfillmentService.isCarrierHandover(yes), yes);
        }
        for (String no : java.util.Arrays.asList("TO_FULFILL", "PROCESSING", "COMPLETED", "CANCELLED", null)) {
            assertFalse(TiktokFulfillmentService.isCarrierHandover(no), String.valueOf(no));
        }
    }
}
