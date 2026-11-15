package com.smartomni.integration.service.order;

import com.smartomni.common.dto.ProcessReturnRequest;
import com.smartomni.common.exception.BusinessException;
import com.smartomni.common.tenant.TenantContext;
import com.smartomni.integration.client.order.OrderServiceClient;
import com.smartomni.integration.client.shopee.ShopeeReturnsClient;
import com.smartomni.integration.client.shopee.ShopeeReturnsClient.ShopeeReturnDetail;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.repository.MarketplaceConnectionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates Shopee return/refund synchronization with Order Service (DS-09, WF-05).
 * Fetches latest return details from Shopee Open API v2, normalizes to ProcessReturnRequest,
 * and delegates to Order Service for status updates and idempotent restock handling.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShopeeReturnSyncService {

    public static final String PLATFORM_SHOPEE = "SHOPEE";

    private final MarketplaceConnectionRepository connectionRepository;
    private final ShopeeReturnsClient returnsClient;
    private final OrderServiceClient orderServiceClient;

    /**
     * Synchronizes a Shopee return request by return SN.
     *
     * @param tenantId Tenant ID owning the connection
     * @param shopId Optional Shopee shop ID
     * @param orderSn Optional Shopee order SN (ordersn)
     * @param returnSn Unique Shopee return SN (returnsn)
     * @return Normalized ProcessReturnRequest dispatched to Order Service
     */
    public ProcessReturnRequest syncReturn(Long tenantId, String shopId, String orderSn, String returnSn) {
        if (returnSn == null || returnSn.isBlank()) {
            throw new BusinessException("returnSn must not be blank", HttpStatus.BAD_REQUEST, "INVALID_RETURN_SN");
        }

        MarketplaceConnection connection = resolveConnection(tenantId, shopId);
        log.info("Syncing Shopee return: tenantId={}, shopId={}, returnSn={}, connectionId={}",
                tenantId, shopId, returnSn, connection.getId());

        ShopeeReturnDetail detail = returnsClient.getReturnDetail(connection.getId(), returnSn);
        if (detail == null) {
            throw new BusinessException("Shopee return detail not found for returnSn=" + returnSn,
                    HttpStatus.NOT_FOUND, "RETURN_NOT_FOUND");
        }

        String resolvedOrderSn = (orderSn != null && !orderSn.isBlank())
                ? orderSn
                : detail.orderSn();

        ProcessReturnRequest request = mapToProcessReturnRequest(connection.getTenantId(), resolvedOrderSn, returnSn, detail);

        try {
            orderServiceClient.processReturn(request);
            log.info("Dispatched Shopee ProcessReturnRequest to OrderService: orderSn={}, returnSn={}, action={}, restock={}",
                    resolvedOrderSn, returnSn, request.getReturnAction(), request.getRestockRequired());
        } catch (Exception ex) {
            log.error("Failed to forward Shopee return to OrderService: {}", ex.getMessage(), ex);
            throw ex;
        }

        return request;
    }

    private MarketplaceConnection resolveConnection(Long tenantId, String shopId) {
        if (tenantId != null && tenantId > 0) {
            try (var scope = TenantContext.openScope(tenantId, null, null)) {
                var connOpt = connectionRepository.findByTenantIdAndPlatform(tenantId, MarketplaceConnection.Platform.SHOPEE);
                if (connOpt.isPresent()) {
                    return connOpt.get();
                }
            }
        }

        if (shopId != null && !shopId.isBlank()) {
            var connOpt = connectionRepository.findFirstByShopId(shopId);
            if (connOpt.isPresent() && connOpt.get().getPlatform() == MarketplaceConnection.Platform.SHOPEE) {
                return connOpt.get();
            }
        }

        throw new BusinessException("Active Shopee connection not found for tenant=" + tenantId + ", shopId=" + shopId,
                HttpStatus.NOT_FOUND, "CONNECTION_NOT_FOUND");
    }

    public ProcessReturnRequest mapToProcessReturnRequest(Long tenantId, String orderSn, String returnSn, ShopeeReturnDetail detail) {
        String status = detail.status();
        String statusUpper = status != null ? status.toUpperCase() : "";
        String reason = detail.reason();
        String reasonUpper = reason != null ? reason.toUpperCase() : "";

        ProcessReturnRequest.ReturnAction returnAction = null;
        boolean restockRequired = false;

        if ("ACCEPTED".equals(statusUpper) || "COMPLETED".equals(statusUpper)) {
            if (reasonUpper.contains("NOT_RECEIPT") || reasonUpper.contains("NOT_RECEIVED")) {
                returnAction = ProcessReturnRequest.ReturnAction.REFUND_ONLY;
                restockRequired = false;
            } else {
                returnAction = ProcessReturnRequest.ReturnAction.RECEIVE_APPROVE;
                restockRequired = true;
            }
        } else if ("SELLER_DISPUTE".equals(statusUpper) || "JUDGING".equals(statusUpper)) {
            returnAction = ProcessReturnRequest.ReturnAction.DISPUTE_CLAIM;
            restockRequired = false;
        } else if ("CANCELLED".equals(statusUpper) || "CLOSED".equals(statusUpper)) {
            returnAction = ProcessReturnRequest.ReturnAction.RECEIVE_REJECT;
            restockRequired = false;
        }

        List<ProcessReturnRequest.ReturnItemDto> items = new ArrayList<>();
        if (detail.items() != null) {
            for (ShopeeReturnsClient.ReturnItem item : detail.items()) {
                items.add(ProcessReturnRequest.ReturnItemDto.builder()
                        .externalItemId(String.valueOf(item.itemId()))
                        .externalModelId(String.valueOf(item.modelId()))
                        .name(item.name())
                        .quantity(item.quantity())
                        .refundAmount(item.refundAmount())
                        .build());
            }
        }

        List<String> evidenceUrls = detail.buyerProofImages() != null ? detail.buyerProofImages() : List.of();

        return ProcessReturnRequest.builder()
                .tenantId(tenantId)
                .platform(PLATFORM_SHOPEE)
                .orderId(orderSn)
                .platformOrderId(orderSn)
                .returnId(returnSn)
                .returnStatus(status)
                .returnReason(reason != null && !reason.isBlank() ? reason : detail.textReason())
                .rejectReason("RECEIVE_REJECT".equals(String.valueOf(returnAction)) ? detail.textReason() : null)
                .returnAction(returnAction)
                .evidenceUrls(evidenceUrls)
                .refundAmount(detail.refundAmount())
                .restockRequired(restockRequired)
                .items(items)
                .build();
    }
}
