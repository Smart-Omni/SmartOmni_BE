package com.smartomni.integration.service.finance;

import com.smartomni.integration.client.shopee.ShopeeFinanceClient;
import com.smartomni.integration.client.shopee.ShopeeFinanceClient.EscrowDetail;
import com.smartomni.integration.client.shopee.ShopeeReturnsClient;
import com.smartomni.integration.client.shopee.ShopeeReturnsClient.EnrichedReturnListResult;
import com.smartomni.integration.client.shopee.ShopeeReturnsClient.EnrichedShopeeReturn;
import com.smartomni.integration.client.shopee.ShopeeReturnsClient.ReturnListResult;
import com.smartomni.integration.client.shopee.ShopeeReturnsClient.ReturnSummary;
import com.smartomni.integration.client.shopee.ShopeeReturnsClient.ShopeeReturnDetail;
import com.smartomni.integration.entity.MarketplaceConnection;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Service orchestrating financial escrow calculations and return dispute management with Shopee.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShopeeFinanceAndReturnsService {

    @Value
    @Builder
    public static class DisputeReturnRequest {
        Integer disputeReasonId;
        String disputeReason;
        String disputeTextReason;
        String email;
        List<String> proofImages;
    }

    @Value
    @Builder
    public static class OfferReturnRequest {
        java.math.BigDecimal offerAmount;
        String offerReason;
    }

    private final ShopeeFinanceClient financeClient;
    private final ShopeeReturnsClient returnsClient;

    public EscrowDetail getEscrowDetail(MarketplaceConnection connection, String orderSn) {
        log.info("Fetching escrow breakdown for order {} (tenant={})", orderSn, connection.getTenantId());
        return financeClient.getEscrowDetail(connection.getId(), orderSn);
    }

    public ReturnListResult listReturns(MarketplaceConnection connection, int pageNo, int pageSize, String status) {
        log.info("Fetching returns for tenant {}: page={}, size={}, status={}",
                connection.getTenantId(), pageNo, pageSize, status);
        return returnsClient.getReturnList(connection.getId(), pageNo, pageSize, status);
    }

    public EnrichedReturnListResult listEnrichedReturns(MarketplaceConnection connection, int pageNo, int pageSize, String status) {
        log.info("Fetching enriched returns for tenant {}: page={}, size={}, status={}",
                connection.getTenantId(), pageNo, pageSize, status);
        ReturnListResult list = returnsClient.getReturnList(connection.getId(), pageNo, pageSize, status);
        List<EnrichedShopeeReturn> enriched = list.returnList().stream()
                .map(summary -> {
                    ShopeeReturnDetail detail = null;
                    try {
                        detail = returnsClient.getReturnDetail(connection.getId(), summary.returnSn());
                    } catch (Exception ex) {
                        log.warn("Failed to fetch return detail for {}, falling back to summary: {}",
                                summary.returnSn(), ex.getMessage());
                    }
                    return mergeReturn(summary, detail);
                })
                .toList();
        return new EnrichedReturnListResult(enriched, list.more(), list.total());
    }

    private static EnrichedShopeeReturn mergeReturn(ReturnSummary s, ShopeeReturnDetail d) {
        return new EnrichedShopeeReturn(
                s.returnSn(),
                s.orderSn(),
                s.status(),
                s.refundAmount(),
                s.currency(),
                s.reason(),
                s.textReason(),
                d != null ? d.buyerUsername() : "",
                firstNonNull(s.createTime(), d != null ? d.createTime() : null),
                firstNonNull(s.updateTime(), d != null ? d.updateTime() : null),
                s.dueDate(),
                firstNonNull(s.disputeDueDate(), d != null ? d.disputeDueDate() : null),
                firstNonNull(s.buyerReturnDueDate(), d != null ? d.buyerReturnDueDate() : null),
                firstNonNull(s.expectedReturnDate(), d != null ? d.expectedReturnDate() : null),
                firstNonNull(s.reverseDeliveredAt(), d != null ? d.reverseDeliveredAt() : null),
                Boolean.TRUE.equals(s.isNfrExempt()) || (d != null && Boolean.TRUE.equals(d.isNfrExempt())),
                firstNonNull(s.needsLogistics(), d != null ? d.needsLogistics() : null),
                firstNonBlank(s.validationType(), d != null ? d.validationType() : null),
                firstNonBlank(s.trackingNumber(), d != null ? d.trackingNumber() : null),
                s.reverseShippingFee() != null ? s.reverseShippingFee() : (d != null ? d.reverseShippingFee() : java.math.BigDecimal.ZERO),
                firstNonBlank(s.negotiationStatus(), d != null ? d.negotiationStatus() : null),
                firstNonNull(s.offerAmount(), d != null ? d.offerAmount() : null),
                firstNonBlank(s.offerReason(), d != null ? d.offerReason() : null),
                firstNonBlank(s.sellerProofStatus(), d != null ? d.sellerProofStatus() : null),
                firstNonBlank(s.sellerCompensationStatus(), d != null ? d.compensationStatus() : null),
                s.disputeReason() != null && !s.disputeReason().isEmpty() ? s.disputeReason() : (d != null ? d.disputeReason() : List.of()),
                s.disputeTextReason() != null && !s.disputeTextReason().isEmpty() ? s.disputeTextReason() : (d != null ? d.disputeTextReason() : List.of()),
                d != null ? d.rulingNotes() : "",
                d != null && d.buyerProofImages() != null && !d.buyerProofImages().isEmpty() ? d.buyerProofImages() : s.proofImages(),
                d != null ? d.sellerProofImages() : List.of(),
                d != null ? d.items() : List.of()
        );
    }

    private static <T> T firstNonNull(T a, T b) {
        return a != null ? a : b;
    }

    private static String firstNonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a : b;
    }

    public ShopeeReturnDetail getReturnDetail(MarketplaceConnection connection, String returnSn) {
        log.info("Fetching return detail for {} (tenant={})", returnSn, connection.getTenantId());
        return returnsClient.getReturnDetail(connection.getId(), returnSn);
    }

    public boolean confirmReturn(MarketplaceConnection connection, String returnSn) {
        log.info("Accepting/confirming return refund {} (tenant={})", returnSn, connection.getTenantId());
        return returnsClient.confirmReturn(connection.getId(), returnSn);
    }

    public boolean offerReturn(MarketplaceConnection connection, String returnSn, OfferReturnRequest request) {
        log.info("Proposing partial refund offer for return {} (tenant={}): amount={}",
                returnSn, connection.getTenantId(), request.getOfferAmount());
        return returnsClient.offerReturn(
                connection.getId(),
                returnSn,
                request.getOfferAmount(),
                request.getOfferReason()
        );
    }

    public boolean disputeReturn(MarketplaceConnection connection, String returnSn, DisputeReturnRequest request) {
        log.info("Submitting dispute for return {} (tenant={}): reason={}",
                returnSn, connection.getTenantId(), request.getDisputeTextReason());

        return returnsClient.disputeReturn(
                connection.getId(),
                returnSn,
                request.getDisputeReasonId(),
                request.getDisputeTextReason(),
                request.getEmail(),
                request.getProofImages()
        );
    }

    public ShopeeReturnsClient.ConvertedImage convertReturnImage(MarketplaceConnection connection, String returnSn, byte[] fileBytes, String filename, String contentType) {
        return returnsClient.convertImage(connection.getId(), returnSn, fileBytes, filename, contentType);
    }

    public List<ShopeeReturnsClient.DisputeReasonOption> getReturnDisputeReasons(MarketplaceConnection connection, String returnSn) {
        return returnsClient.getReturnDisputeReasons(connection.getId(), returnSn);
    }

    public boolean uploadReturnProof(MarketplaceConnection connection, String returnSn, String description, List<ShopeeReturnsClient.ConvertedImage> photos) {
        return returnsClient.uploadProof(connection.getId(), returnSn, description, photos);
    }

    public com.fasterxml.jackson.databind.JsonNode queryReturnProof(MarketplaceConnection connection, String returnSn) {
        return returnsClient.queryProof(connection.getId(), returnSn);
    }
}
