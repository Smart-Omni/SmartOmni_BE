package com.smartomni.integration.service.tiktok;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartomni.common.exception.BusinessException;
import com.smartomni.integration.client.tiktok.TiktokApiException;
import com.smartomni.integration.client.tiktok.TiktokReturnClient;
import com.smartomni.integration.dto.tiktok.TiktokAftersaleCodesDto;
import com.smartomni.integration.dto.tiktok.TiktokAftersaleCodesDto.ReasonCodeItem;
import com.smartomni.integration.dto.tiktok.TiktokAftersaleCodesDto.StatusCodeItem;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.service.catalog.TiktokTenantConnections;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * D9 service providing TikTok Shop aftersale reason codes, reject reasons, status reference,
 * and live Simulator lookups ({@code reject_reasons}, {@code aftersale_eligibility}).
 */
@Service
@RequiredArgsConstructor
public class TiktokAftersaleCodesService {

    public static final List<ReasonCodeItem> BUYER_CANCEL_REASONS = List.of(
            new ReasonCodeItem("CHANGED_MIND", "Buyer changed their mind", "Khách hàng đổi ý, không muốn mua nữa"),
            new ReasonCodeItem("ORDERED_BY_MISTAKE", "Ordered by mistake or duplicate order", "Đặt nhầm sản phẩm hoặc đặt trùng đơn"),
            new ReasonCodeItem("WRONG_ADDRESS", "Wrong shipping address", "Sai địa chỉ nhận hàng"),
            new ReasonCodeItem("DAMAGED_ITEM", "Item arrived damaged", "Sản phẩm bị hư hỏng"),
            new ReasonCodeItem("NOT_AS_DESCRIBED", "Item not as described", "Sản phẩm không đúng mô tả"),
            new ReasonCodeItem("WRONG_ITEM", "Wrong item delivered", "Giao sai sản phẩm"),
            new ReasonCodeItem("OTHER", "Other reason", "Lý do khác")
    );

    public static final List<ReasonCodeItem> SELLER_CANCEL_REASONS = List.of(
            new ReasonCodeItem("OUT_OF_STOCK", "Out of stock", "Hết hàng trong kho"),
            new ReasonCodeItem("PRICING_ERROR", "Pricing or listing error", "Sai giá hoặc sai thông tin sản phẩm"),
            new ReasonCodeItem("UNABLE_TO_DELIVER", "Unable to deliver to buyer address", "Không thể giao đến địa chỉ người nhận"),
            new ReasonCodeItem("BUYER_REQUESTED", "Buyer requested cancellation", "Người mua yêu cầu hủy đơn"),
            new ReasonCodeItem("OTHER", "Other reason", "Lý do khác")
    );

    public static final List<ReasonCodeItem> RETURN_REQUEST_REASONS = List.of(
            new ReasonCodeItem("CHANGED_MIND", "Buyer changed their mind", "Khách hàng đổi ý"),
            new ReasonCodeItem("DAMAGED_ITEM", "Damaged item", "Sản phẩm bị hư hỏng / bể vỡ"),
            new ReasonCodeItem("NOT_AS_DESCRIBED", "Not as described", "Sản phẩm không đúng mô tả"),
            new ReasonCodeItem("WRONG_ITEM", "Wrong item received", "Giao sai sản phẩm / sai phân loại"),
            new ReasonCodeItem("OTHER", "Other reason", "Lý do khác")
    );

    public static final List<ReasonCodeItem> REJECT_REASONS = List.of(
            new ReasonCodeItem("ITEM_AS_DESCRIBED", "The item matches its description", "Sản phẩm đúng như mô tả"),
            new ReasonCodeItem("RETURN_WINDOW_EXPIRED", "The request is outside the return window", "Quá thời hạn yêu cầu trả hàng/hoàn tiền"),
            new ReasonCodeItem("ITEM_USED_OR_DAMAGED", "The returned item is used or damaged", "Sản phẩm trả lại đã qua sử dụng hoặc bị hư hỏng"),
            new ReasonCodeItem("ALREADY_SHIPPED", "The order has already been handed to the carrier", "Đơn hàng đã được bàn giao cho đơn vị vận chuyển"),
            new ReasonCodeItem("OTHER", "Other reason", "Lý do khác")
    );

    public static final List<StatusCodeItem> RETURN_STATUSES = List.of(
            new StatusCodeItem("RETURN_OR_REFUND_REQUEST_PENDING", "Chờ người bán xử lý yêu cầu", false),
            new StatusCodeItem("AWAITING_BUYER_SHIP", "Người bán đã duyệt trả hàng, chờ người mua gửi hàng hoàn", false),
            new StatusCodeItem("BUYER_SHIPPED_ITEM", "Người mua đã gửi hàng hoàn cho đơn vị vận chuyển", false),
            new StatusCodeItem("RETURN_OR_REFUND_REQUEST_SUCCESS", "Người bán đã duyệt hoàn tiền (chờ giải ngân)", false),
            new StatusCodeItem("RETURN_OR_REFUND_REQUEST_COMPLETE", "Đã hoàn tiền xong", true),
            new StatusCodeItem("REFUND_OR_RETURN_REQUEST_REJECT", "Người bán từ chối yêu cầu ngay từ bước PENDING", true),
            new StatusCodeItem("REJECT_RECEIVE_PACKAGE", "Người bán từ chối sau khi nhận hàng hoàn", true),
            new StatusCodeItem("RETURN_OR_REFUND_REQUEST_CANCEL", "Người mua tự hủy/rút yêu cầu trả hàng", true)
    );

    public static final List<StatusCodeItem> CANCEL_STATUSES = List.of(
            new StatusCodeItem("CANCELLATION_REQUEST_PENDING", "Khách gửi yêu cầu hủy đơn, chờ shop duyệt", false),
            new StatusCodeItem("CANCELLATION_REQUEST_SUCCESS", "Yêu cầu hủy đã được duyệt", false),
            new StatusCodeItem("CANCELLATION_REQUEST_CANCELLED", "Shop từ chối yêu cầu hủy của khách", true),
            new StatusCodeItem("CANCELLATION_REQUEST_COMPLETE", "Hoàn tiền hủy đơn hoàn tất", true)
    );

    private static final Set<String> VALID_REJECT_REASON_CODES = Set.of(
            "ITEM_AS_DESCRIBED",
            "RETURN_WINDOW_EXPIRED",
            "ITEM_USED_OR_DAMAGED",
            "ALREADY_SHIPPED",
            "OTHER"
    );

    private static final Map<String, String> REJECT_VI_BY_NAME = Map.of(
            "ITEM_AS_DESCRIBED", "Sản phẩm đúng như mô tả",
            "RETURN_WINDOW_EXPIRED", "Quá thời hạn yêu cầu trả hàng/hoàn tiền",
            "ITEM_USED_OR_DAMAGED", "Sản phẩm trả lại đã qua sử dụng hoặc bị hư hỏng",
            "ALREADY_SHIPPED", "Đơn hàng đã được bàn giao cho đơn vị vận chuyển",
            "OTHER", "Lý do khác"
    );

    private final TiktokTenantConnections tenantConnections;
    private final TiktokReturnClient returnClient;

    public TiktokAftersaleCodesDto getAllCodes() {
        return TiktokAftersaleCodesDto.builder()
                .buyerCancelReasons(BUYER_CANCEL_REASONS)
                .sellerCancelReasons(SELLER_CANCEL_REASONS)
                .returnRequestReasons(RETURN_REQUEST_REASONS)
                .rejectReasons(REJECT_REASONS)
                .returnStatuses(RETURN_STATUSES)
                .cancelStatuses(CANCEL_STATUSES)
                .build();
    }

    public void validateRejectReason(String rejectReason) {
        if (rejectReason == null || rejectReason.isBlank() || !VALID_REJECT_REASON_CODES.contains(rejectReason.trim())) {
            throw new BusinessException(
                    "Invalid reject_reason '" + rejectReason + "'. Allowed values: " + VALID_REJECT_REASON_CODES,
                    HttpStatus.BAD_REQUEST,
                    "INVALID_REJECT_REASON");
        }
    }

    public List<ReasonCodeItem> getRejectReasons(String returnOrCancelId) {
        if (returnOrCancelId == null || returnOrCancelId.isBlank()) {
            return REJECT_REASONS;
        }
        MarketplaceConnection connection = tenantConnections.requireConnected();
        try {
            JsonNode data = returnClient.getRejectReasons(connection.getId(), returnOrCancelId.trim());
            JsonNode reasonsNode = data != null ? data.path("reasons") : null;
            if (reasonsNode != null && reasonsNode.isArray() && !reasonsNode.isEmpty()) {
                List<ReasonCodeItem> list = new ArrayList<>();
                for (JsonNode node : reasonsNode) {
                    String name = node.path("name").asText("");
                    String text = node.path("text").asText("");
                    if (!name.isBlank()) {
                        list.add(new ReasonCodeItem(name, text, REJECT_VI_BY_NAME.getOrDefault(name, text)));
                    }
                }
                if (!list.isEmpty()) {
                    return list;
                }
            }
            return REJECT_REASONS;
        } catch (TiktokApiException e) {
            HttpStatus status = e.getHttpStatus() == 404 ? HttpStatus.NOT_FOUND
                    : (e.getHttpStatus() == 400 ? HttpStatus.BAD_REQUEST : HttpStatus.BAD_GATEWAY);
            throw new BusinessException(e.getMessage(), status, "AFTERSALE_REJECT_REASONS_FAILED");
        }
    }

    public JsonNode getAftersaleEligibility(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            throw new BusinessException("orderId is required", HttpStatus.BAD_REQUEST, "INVALID_ORDER_ID");
        }
        MarketplaceConnection connection = tenantConnections.requireConnected();
        try {
            return returnClient.getAftersaleEligibility(connection.getId(), orderId.trim());
        } catch (TiktokApiException e) {
            HttpStatus status = e.getHttpStatus() == 404 ? HttpStatus.NOT_FOUND
                    : (e.getHttpStatus() == 400 ? HttpStatus.BAD_REQUEST : HttpStatus.BAD_GATEWAY);
            throw new BusinessException(e.getMessage(), status, "AFTERSALE_ELIGIBILITY_FAILED");
        }
    }
}
