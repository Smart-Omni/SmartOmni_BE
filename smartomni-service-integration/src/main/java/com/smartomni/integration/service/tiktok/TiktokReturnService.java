package com.smartomni.integration.service.tiktok;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartomni.common.dto.ProcessReturnRequest;
import com.smartomni.common.exception.BusinessException;
import com.smartomni.integration.client.order.OrderServiceClient;
import com.smartomni.integration.client.tiktok.TiktokReturnClient;
import com.smartomni.integration.dto.tiktok.ReturnSearchRequest;
import com.smartomni.integration.dto.tiktok.ReturnSearchResponse;
import com.smartomni.integration.dto.tiktok.TiktokWebhookPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Service tiếp nhận và xử lý yêu cầu Trả hàng / Hoàn tiền (Returns & Refund Management - Bước 4.2).
 * - Tiếp nhận event RETURN_STATUS_CHANGE từ Webhook (type = 2 hoặc 4, hoặc return_status).
 * - Bóc tách return_id, return_type, return_reason, refund_amount, restockRequired.
 * - Gọi OrderServiceClient để gửi yêu cầu xử lý sang Order Service.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TiktokReturnService {

    public static final String PLATFORM_TIKTOK_SHOP = "TIKTOK_SHOP";

    private final TiktokReturnClient returnClient;
    private final OrderServiceClient orderServiceClient;
    private final ObjectMapper objectMapper;

    /**
     * Tra cứu danh sách yêu cầu trả hàng từ sàn qua API.
     */
    public ReturnSearchResponse searchReturns(Long connectionId, ReturnSearchRequest request) {
        if (connectionId == null || connectionId <= 0) {
            throw new BusinessException("connectionId must be positive", HttpStatus.BAD_REQUEST, "INVALID_CONNECTION_ID");
        }
        return returnClient.searchReturns(connectionId, request != null ? request : new ReturnSearchRequest());
    }

    /**
     * Xử lý webhook RETURN_STATUS_CHANGE / REVERSE_STATUS_CHANGE từ TikTok Shop.
     *
     * @param tenantId       ID của tenant sở hữu shop
     * @param webhookPayload Webhook payload nhận từ TikTok
     * @return DTO ProcessReturnRequest đã gửi sang Order Service
     */
    public ProcessReturnRequest handleReturnWebhook(Long tenantId, TiktokWebhookPayload webhookPayload) {
        if (tenantId == null || tenantId <= 0) {
            throw new BusinessException("tenantId is required and must be positive", HttpStatus.BAD_REQUEST, "INVALID_TENANT_ID");
        }
        if (webhookPayload == null || webhookPayload.getData() == null) {
            throw new BusinessException("Webhook payload data is required", HttpStatus.BAD_REQUEST, "INVALID_PAYLOAD");
        }

        return handleReturnStatusChange(tenantId, webhookPayload.getData());
    }

    /**
     * Bóc tách dữ liệu JSON chi tiết của sự kiện trả hàng / hoàn tiền.
     */
    public ProcessReturnRequest handleReturnStatusChange(Long tenantId, JsonNode data) {
        if (data == null || data.isMissingNode() || data.isNull()) {
            throw new BusinessException("Return data must not be empty", HttpStatus.BAD_REQUEST, "INVALID_RETURN_DATA");
        }

        // 1. Bóc tách return_id
        String returnId = data.path("return_id").asText(null);

        // 2. Bóc tách order_id / platformOrderId
        String orderId = null;
        if (data.hasNonNull("order_id")) {
            orderId = data.get("order_id").asText();
        } else if (data.hasNonNull("platform_order_id")) {
            orderId = data.get("platform_order_id").asText();
        }

        // 3. Bóc tách return_type (RETURN_AND_REFUND, REFUND_ONLY, REPLACEMENT)
        String returnType = null;
        if (data.hasNonNull("return_type")) {
            returnType = data.get("return_type").asText();
        }

        // 4. Bóc tách return_status
        String returnStatus = null;
        if (data.hasNonNull("return_status")) {
            returnStatus = data.get("return_status").asText();
        } else if (data.hasNonNull("status")) {
            returnStatus = data.get("status").asText();
        }

        // 5. Bóc tách lý do trả hàng
        String returnReason = null;
        if (data.hasNonNull("return_reason_text")) {
            returnReason = data.get("return_reason_text").asText();
        } else if (data.hasNonNull("return_reason")) {
            returnReason = data.get("return_reason").asText();
        } else if (data.hasNonNull("reason")) {
            returnReason = data.get("reason").asText();
        }

        // 6. Bóc tách số tiền hoàn refund_amount
        BigDecimal refundAmount = BigDecimal.ZERO;
        if (data.hasNonNull("refund_amount")) {
            try {
                refundAmount = new BigDecimal(data.get("refund_amount").asText());
            } catch (Exception ignored) {}
        }

        // 7. Xác định restockRequired:
        // Cần nhập lại kho khi trả hàng vật lý về kho (RETURN_AND_REFUND) và đã được chấp nhận/hoàn tất
        boolean restockRequired = isRestockRequired(returnType, returnStatus, data);

        ProcessReturnRequest request = ProcessReturnRequest.builder()
                .tenantId(tenantId)
                .platform(PLATFORM_TIKTOK_SHOP)
                .orderId(orderId)
                .platformOrderId(orderId)
                .returnId(returnId)
                .returnType(returnType)
                .returnStatus(returnStatus)
                .returnReason(returnReason)
                .refundAmount(refundAmount)
                .restockRequired(restockRequired)
                .build();

        log.info("Xử lý RETURN_STATUS_CHANGE: tenantId={}, orderId={}, returnId={}, returnStatus={}, restockRequired={}",
                tenantId, orderId, returnId, returnStatus, restockRequired);

        // 8. Gửi yêu cầu sang Order Service
        try {
            orderServiceClient.processReturn(request);
            log.info("Đã gửi yêu cầu xử lý trả hàng sang OrderService thành công: orderId={}", orderId);
        } catch (Exception ex) {
            log.error("Lỗi khi gửi yêu cầu xử lý trả hàng sang OrderService: {}", ex.getMessage(), ex);
        }

        return request;
    }

    private boolean isRestockRequired(String returnType, String returnStatus, JsonNode data) {
        if (data.hasNonNull("restock_required")) {
            return data.get("restock_required").asBoolean();
        }

        if (returnType != null && returnType.equalsIgnoreCase("REFUND_ONLY")) {
            return false;
        }

        if (returnStatus == null) {
            return false;
        }

        String statusUpper = returnStatus.toUpperCase();
        return statusUpper.contains("COMPLETE")
                || statusUpper.contains("SUCCESS")
                || statusUpper.contains("APPROVED")
                || statusUpper.equals("RETURNED");
    }
}
