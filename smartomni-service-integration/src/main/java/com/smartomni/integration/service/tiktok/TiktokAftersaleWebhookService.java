package com.smartomni.integration.service.tiktok;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartomni.common.exception.BusinessException;
import com.smartomni.integration.client.tiktok.TiktokCancellationClient;
import com.smartomni.integration.client.tiktok.TiktokOrderClient;
import com.smartomni.integration.client.tiktok.TiktokReturnClient;
import com.smartomni.integration.dto.tiktok.TiktokCancellationResponse;
import com.smartomni.integration.dto.tiktok.TiktokOrderDetailResponse;
import com.smartomni.integration.dto.tiktok.TiktokReturnResponse;
import com.smartomni.integration.entity.MarketplaceConnection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Handles {@code RETURN_STATUS_CHANGE} (12, also {@code REVERSE_STATUS_UPDATE} 4) and
 * {@code CANCELLATION_STATUS_CHANGE} (11) webhooks, and the same records found by polling.
 *
 * <p>The event payload is only a hint: the full record is read again from TikTok (Search Returns / Search
 * Cancellations filtered by id) and its CURRENT state is applied. An old or reordered event therefore never applies
 * its own outdated status; when its {@code update_time} is older than the record's it is only logged as stale.
 *
 * <p>Returns go to the order service ({@code processReturn}). A return that covers fewer units than the order is
 * sent as {@code partial_return}, so the whole order keeps its status. Restock: TikTok orders never deduct
 * SmartOmni stock and TikTok stock follows the {@code INVENTORY_CHANGED_BY_SHOP} snapshot, so no restock is ever
 * requested for a TikTok return or cancellation (the order service records {@code PLATFORM_SYNC}).
 *
 * <p>Cancellations are checked and logged only: when TikTok completes a cancellation it moves the order to
 * CANCELLED and emits {@code ORDER_STATUS_CHANGE}, which the order flow applies. Forwarding a pending cancel
 * request through the return flow would put the order ON_HOLD.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TiktokAftersaleWebhookService {

    private final TiktokReturnClient returnClient;
    private final TiktokCancellationClient cancellationClient;
    private final TiktokOrderClient orderClient;
    private final TiktokReturnService returnService;

    /** @return the current record that was applied, or {@code null} when TikTok does not return it */
    public TiktokReturnResponse handleReturnEvent(Long tenantId, MarketplaceConnection connection, JsonNode data) {
        String returnId = firstText(data, "return_id", "reverse_id");
        TiktokReturnResponse current = returnClient.getReturnDetail(connection.getId(), returnId);
        if (current == null) {
            throw new BusinessException("TikTok did not return return " + returnId + " yet",
                    HttpStatus.SERVICE_UNAVAILABLE, "TIKTOK_RETURN_NOT_READY");
        }
        logIfStale("return", returnId, data, current.getUpdateTime());
        applyReturn(tenantId, connection, current);
        return current;
    }

    /** Applies the current state of a return already read from TikTok (webhook or polling). */
    public void applyReturn(Long tenantId, MarketplaceConnection connection, TiktokReturnResponse current) {
        boolean partial = isPartial(connection, current);
        returnService.forwardCurrentReturn(tenantId, connection.getId(), current, partial);
        log.info("TikTok return {} of order {} applied: status={} partial={}", current.getReturnId(),
                current.getOrderId(), current.getReturnStatus(), partial);
    }

    /** @return the current cancellation record, or {@code null}-safe 503 when TikTok does not return it yet */
    public TiktokCancellationResponse handleCancellationEvent(Long tenantId, MarketplaceConnection connection, JsonNode data) {
        String cancelId = firstText(data, "cancel_id");
        TiktokCancellationResponse current = cancellationClient.getCancellation(connection.getId(), cancelId);
        if (current == null) {
            throw new BusinessException("TikTok did not return cancellation " + cancelId + " yet",
                    HttpStatus.SERVICE_UNAVAILABLE, "TIKTOK_CANCELLATION_NOT_READY");
        }
        logIfStale("cancellation", cancelId, data, current.getUpdateTime());
        log.info("TikTok cancellation {} of order {} (tenant {}): status={} role={}", current.getCancelId(),
                current.getOrderId(), tenantId, current.getCancelStatus(), current.getRole());
        return current;
    }

    /** Fewer returned units than order line items (TikTok lists one line item per unit). */
    private boolean isPartial(MarketplaceConnection connection, TiktokReturnResponse ret) {
        int returned = ret.getReturnLineItems() == null ? 0 : ret.getReturnLineItems().size();
        if (returned == 0) {
            return false; // no line detail: treat as a whole-order return, as before
        }
        TiktokOrderDetailResponse order = orderClient.getOrderDetail(connection.getId(), ret.getOrderId());
        int ordered = order == null || order.getLineItems() == null ? 0 : order.getLineItems().size();
        return ordered > 0 && returned < ordered;
    }

    private static void logIfStale(String kind, String id, JsonNode data, Long currentUpdateTime) {
        long eventTime = data == null ? 0 : data.path("update_time").asLong(0);
        if (eventTime > 0 && currentUpdateTime != null && eventTime < currentUpdateTime) {
            log.info("Stale TikTok {} event for {}: event update_time {} < current {}; current state applied",
                    kind, id, eventTime, currentUpdateTime);
        }
    }

    private static String firstText(JsonNode data, String... fields) {
        if (data != null) {
            for (String field : fields) {
                String value = data.path(field).asText(null);
                if (value != null && !value.isBlank()) {
                    return value.trim();
                }
            }
        }
        throw new BusinessException("Webhook data has no " + String.join("/", fields), HttpStatus.BAD_REQUEST, "INVALID_PAYLOAD");
    }
}
