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
import com.smartomni.integration.dto.tiktok.TiktokWebhookPayload;
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
     * Xử lý sự kiện Webhook PACKAGE_STATUS_CHANGE từ TikTok Shop.
     * Bóc tách package_id, tracking_number, shipping_provider và cập nhật vào smartomni-service-order.
     *
     * @param tenantId       ID của tenant sở hữu shop
     * @param webhookPayload Webhook payload nhận từ TikTok
     * @return DTO UpdateOrderTrackingRequest đã gửi sang Order Service
     */
    public UpdateOrderTrackingRequest handlePackageStatusChange(Long tenantId, TiktokWebhookPayload webhookPayload) {
        if (tenantId == null || tenantId <= 0) {
            throw new BusinessException("tenantId is required and must be positive", HttpStatus.BAD_REQUEST, "INVALID_TENANT_ID");
        }
        if (webhookPayload == null || webhookPayload.getData() == null) {
            throw new BusinessException("Webhook payload data is required", HttpStatus.BAD_REQUEST, "INVALID_PAYLOAD");
        }

        return handlePackageStatusChange(tenantId, webhookPayload.getData());
    }

    /**
     * Xử lý dữ liệu JsonNode chi tiết của sự kiện PACKAGE_STATUS_CHANGE.
     */
    public UpdateOrderTrackingRequest handlePackageStatusChange(Long tenantId, JsonNode data) {
        if (data == null || data.isMissingNode() || data.isNull()) {
            throw new BusinessException("Package data must not be empty", HttpStatus.BAD_REQUEST, "INVALID_PACKAGE_DATA");
        }

        // 1. Bóc tách package_id
        String packageId = data.path("package_id").asText(null);

        // 2. Bóc tách tracking_number
        String trackingNumber = data.path("tracking_number").asText(null);

        // 3. Bóc tách shipping_provider
        String shippingProvider = null;
        if (data.hasNonNull("shipping_provider_name")) {
            shippingProvider = data.get("shipping_provider_name").asText();
        } else if (data.hasNonNull("shipping_provider")) {
            shippingProvider = data.get("shipping_provider").asText();
        } else if (data.hasNonNull("shipping_provider_id")) {
            shippingProvider = data.get("shipping_provider_id").asText();
        }

        // 4. Bóc tách trạng thái kiện hàng
        String packageStatus = null;
        if (data.hasNonNull("package_status")) {
            packageStatus = data.get("package_status").asText();
        } else if (data.hasNonNull("status")) {
            packageStatus = data.get("status").asText();
        }

        // 5. Bóc tách danh sách mã đơn hàng liên quan
        List<String> orderIdList = new ArrayList<>();
        if (data.has("order_id_list") && data.get("order_id_list").isArray()) {
            for (JsonNode node : data.get("order_id_list")) {
                if (node.isTextual() && !node.asText().isBlank()) {
                    orderIdList.add(node.asText());
                }
            }
        }
        if (orderIdList.isEmpty() && data.hasNonNull("order_id")) {
            orderIdList.add(data.get("order_id").asText());
        }

        String platformOrderId = !orderIdList.isEmpty() ? orderIdList.get(0) : null;

        // 6. Tính toán courierScannedAt khi trạng thái chuyển sang IN_TRANSIT hoặc PICKED_UP
        Instant courierScannedAt = null;
        boolean isHandover = packageStatus != null && (
                packageStatus.equalsIgnoreCase("IN_TRANSIT")
                        || packageStatus.equalsIgnoreCase("PICKED_UP")
                        || packageStatus.equalsIgnoreCase("SHIPPED"));

        if (isHandover) {
            long updateTime = data.path("update_time").asLong(0);
            if (updateTime > 0) {
                courierScannedAt = (updateTime > 100_000_000_000L)
                        ? Instant.ofEpochMilli(updateTime)
                        : Instant.ofEpochSecond(updateTime);
            } else {
                courierScannedAt = Instant.now();
            }
        }

        // 7. Tạo DTO cập nhật tracking cho Order Service
        UpdateOrderTrackingRequest updateRequest = UpdateOrderTrackingRequest.builder()
                .tenantId(tenantId)
                .platform(PLATFORM_TIKTOK_SHOP)
                .platformOrderId(platformOrderId)
                .orderIdList(orderIdList)
                .packageId(packageId)
                .trackingNumber(trackingNumber)
                .shippingProvider(shippingProvider)
                .packageStatus(packageStatus)
                .courierScannedAt(courierScannedAt)
                .build();

        log.info("Xử lý PACKAGE_STATUS_CHANGE: tenantId={}, packageId={}, tracking={}, provider={}, status={}, scannedAt={}",
                tenantId, packageId, trackingNumber, shippingProvider, packageStatus, courierScannedAt);

        // 8. Gọi OrderServiceClient để cập nhật vào bảng orders
        try {
            orderServiceClient.updateTracking(updateRequest);
            log.info("Đã gửi yêu cầu cập nhật thông tin vận đơn sang OrderService thành công");
        } catch (Exception ex) {
            log.error("Lỗi khi gọi OrderService để cập nhật vận đơn: {}", ex.getMessage(), ex);
        }

        return updateRequest;
    }
}
