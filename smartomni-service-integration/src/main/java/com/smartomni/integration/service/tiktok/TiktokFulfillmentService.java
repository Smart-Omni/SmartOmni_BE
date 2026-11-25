package com.smartomni.integration.service.tiktok;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartomni.common.dto.UpdateOrderTrackingRequest;
import com.smartomni.common.exception.BusinessException;
import com.smartomni.integration.client.order.OrderServiceClient;
import com.smartomni.integration.client.tiktok.TiktokFulfillmentClient;
import com.smartomni.integration.dto.tiktok.ShipPackageRequest;
import com.smartomni.integration.dto.tiktok.ShippingDocumentResponse;
import com.smartomni.integration.dto.tiktok.TiktokPackageResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Service quản lý giao vận và Fulfillment với TikTok Shop (Bước 4.1).
 * Hỗ trợ:
 * - Xác nhận gửi hàng / Arrange Shipment qua TiktokFulfillmentClient.
 * - Lấy tài liệu vận đơn / Tem nhãn Waybill PDF qua TiktokFulfillmentClient.
 * - Xử lý sự kiện PACKAGE_STATUS_CHANGE từ Webhook: bóc tách package_id, tracking_number,
 *   shipping_provider, courierScannedAt và đồng bộ sang smartomni-service-order.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TiktokFulfillmentService {

    public static final String DEFAULT_DOCUMENT_TYPE = "SL_PDF";
    public static final String PLATFORM_TIKTOK_SHOP = "TIKTOK_SHOP";

    private final TiktokFulfillmentClient fulfillmentClient;
    private final OrderServiceClient orderServiceClient;
    private final ObjectMapper objectMapper;

    /**
     * Xác nhận gửi hàng với TikTok Simulator / Open Platform.
     *
     * @param connectionId ID kết nối sàn của tenant
     * @param packageId    Mã kiện hàng (package_id)
     * @param request      Thông tin pickup slot hoặc self shipment
     * @return Thông tin package response từ TikTok
     */
    public TiktokPackageResponse arrangeShipment(Long connectionId, String packageId, ShipPackageRequest request) {
        if (connectionId == null || connectionId <= 0) {
            throw new BusinessException("connectionId must be positive", HttpStatus.BAD_REQUEST, "INVALID_CONNECTION_ID");
        }
        if (packageId == null || packageId.isBlank()) {
            throw new BusinessException("packageId must not be blank", HttpStatus.BAD_REQUEST, "INVALID_PACKAGE_ID");
        }

        log.info("Bắt đầu xác nhận gửi hàng (Arrange Shipment): connectionId={}, packageId={}", connectionId, packageId);
        TiktokPackageResponse response = fulfillmentClient.shipPackage(connectionId, packageId, request);
        log.info("Xác nhận gửi hàng thành công: connectionId={}, packageId={}, status={}",
                connectionId, packageId, response != null ? response.getStatus() : null);
        return response;
    }

    /**
     * Lấy tài liệu tem nhãn vận chuyển (Shipping Label / Waybill PDF).
     *
     * @param connectionId ID kết nối sàn của tenant
     * @param packageId    Mã kiện hàng
     * @return Đối tượng ShippingDocumentResponse chứa URL tem nhãn
     */
    public ShippingDocumentResponse getShippingLabel(Long connectionId, String packageId) {
        if (connectionId == null || connectionId <= 0) {
            throw new BusinessException("connectionId must be positive", HttpStatus.BAD_REQUEST, "INVALID_CONNECTION_ID");
        }
        if (packageId == null || packageId.isBlank()) {
            throw new BusinessException("packageId must not be blank", HttpStatus.BAD_REQUEST, "INVALID_PACKAGE_ID");
        }

        log.info("Lấy tài liệu tem nhãn PDF: connectionId={}, packageId={}", connectionId, packageId);
        return fulfillmentClient.getShippingDocuments(connectionId, packageId, DEFAULT_DOCUMENT_TYPE);
    }

    /**
     * Lấy trực tiếp URL tem nhãn PDF.
     */
    public String getShippingLabelUrl(Long connectionId, String packageId) {
        ShippingDocumentResponse document = getShippingLabel(connectionId, packageId);
        if (document == null) {
            return null;
        }
        return (document.getDocUrl() != null && !document.getDocUrl().isBlank())
                ? document.getDocUrl()
                : document.getFileUrl();
    }

    /**
     * PACKAGE_UPDATE webhook (type 3). The payload only carries {@code package_id}, {@code order_id},
     * {@code package_status} and {@code update_time}, so the tracking number and carrier are read from Get Package
     * Detail, then sent to the order service. Tracking never changes the order status (ORDER_STATUS_CHANGE does).
     *
     * <p>Runs inside the tenant scope opened by the webhook controller. TikTok and order service failures propagate
     * so the controller answers 503 and TikTok redelivers.
     *
     * @return the request sent to the order service, or {@code null} when the event names no order
     */
    public UpdateOrderTrackingRequest handlePackageStatusChange(Long tenantId, Long connectionId, JsonNode data) {
        if (tenantId == null || tenantId <= 0) {
            throw new BusinessException("tenantId is required and must be positive", HttpStatus.BAD_REQUEST, "INVALID_TENANT_ID");
        }
        if (data == null || data.isMissingNode() || data.isNull()) {
            throw new BusinessException("Package data must not be empty", HttpStatus.BAD_REQUEST, "INVALID_PACKAGE_DATA");
        }

        String packageId = text(data, "package_id");
        String packageStatus = firstText(data, "package_status", "status");
        List<String> orderIdList = orderIds(data);
        String trackingNumber = text(data, "tracking_number");
        String shippingProvider = firstText(data, "shipping_provider_name", "shipping_provider", "shipping_provider_id");

        if ((trackingNumber == null || shippingProvider == null) && packageId != null && connectionId != null) {
            TiktokPackageResponse detail = fulfillmentClient.getPackageDetail(connectionId, packageId);
            if (detail != null) {
                trackingNumber = firstNonBlank(trackingNumber, detail.getTrackingNumber());
                shippingProvider = firstNonBlank(shippingProvider, detail.getShippingProviderName(),
                        detail.getShippingProviderId());
                packageStatus = firstNonBlank(packageStatus, detail.getPackageStatus(), detail.getStatus());
                if (orderIdList.isEmpty() && detail.getOrderIdList() != null) {
                    detail.getOrderIdList().stream().filter(id -> id != null && !id.isBlank()).forEach(orderIdList::add);
                }
            }
        }

        if (orderIdList.isEmpty()) {
            log.warn("PACKAGE_UPDATE không có order_id, bỏ qua: tenantId={}, packageId={}", tenantId, packageId);
            return null;
        }

        UpdateOrderTrackingRequest updateRequest = UpdateOrderTrackingRequest.builder()
                .tenantId(tenantId)
                .platform(PLATFORM_TIKTOK_SHOP)
                .platformOrderId(orderIdList.get(0))
                .orderIdList(orderIdList)
                .packageId(packageId)
                .trackingNumber(trackingNumber)
                .shippingProvider(shippingProvider)
                .packageStatus(packageStatus)
                .courierScannedAt(isCarrierHandover(packageStatus) ? eventTime(data) : null)
                .build();

        log.info("Xử lý PACKAGE_UPDATE: tenantId={}, packageId={}, tracking={}, provider={}, status={}, scannedAt={}",
                tenantId, packageId, trackingNumber, shippingProvider, packageStatus, updateRequest.getCourierScannedAt());
        orderServiceClient.updateTracking(updateRequest);
        return updateRequest;
    }

    /**
     * The carrier has the package. TikTok package statuses are TO_FULFILL, PROCESSING (awaiting collection),
     * FULFILLING (in transit), COMPLETED and CANCELLED; the other values are kept for older payloads.
     */
    static boolean isCarrierHandover(String packageStatus) {
        if (packageStatus == null) {
            return false;
        }
        return switch (packageStatus.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "FULFILLING", "IN_TRANSIT", "PICKED_UP", "SHIPPED" -> true;
            default -> false;
        };
    }

    private static Instant eventTime(JsonNode data) {
        long updateTime = data.path("update_time").asLong(0);
        if (updateTime <= 0) {
            return Instant.now();
        }
        return updateTime > 100_000_000_000L ? Instant.ofEpochMilli(updateTime) : Instant.ofEpochSecond(updateTime);
    }

    private static List<String> orderIds(JsonNode data) {
        List<String> ids = new ArrayList<>();
        if (data.path("order_id_list").isArray()) {
            for (JsonNode node : data.get("order_id_list")) {
                if (node.isTextual() && !node.asText().isBlank()) {
                    ids.add(node.asText());
                }
            }
        }
        String single = text(data, "order_id");
        if (ids.isEmpty() && single != null) {
            ids.add(single);
        }
        return ids;
    }

    private static String firstText(JsonNode data, String... fields) {
        for (String field : fields) {
            String value = text(data, field);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String text(JsonNode data, String field) {
        JsonNode node = data.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asText();
        return value.isBlank() ? null : value;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
