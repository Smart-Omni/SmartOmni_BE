package com.smartomni.integration.service.finance;

import com.smartomni.integration.client.shopee.ShopeeFinanceClient;
import com.smartomni.integration.client.shopee.ShopeeFinanceClient.EscrowDetail;
import com.smartomni.integration.client.shopee.ShopeeReturnsClient;
import com.smartomni.integration.client.shopee.ShopeeReturnsClient.ReturnListResult;
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

    public ShopeeReturnDetail getReturnDetail(MarketplaceConnection connection, String returnSn) {
        log.info("Fetching return detail for {} (tenant={})", returnSn, connection.getTenantId());
        return returnsClient.getReturnDetail(connection.getId(), returnSn);
    }

    public boolean confirmReturn(MarketplaceConnection connection, String returnSn) {
        log.info("Accepting/confirming return refund {} (tenant={})", returnSn, connection.getTenantId());
        return returnsClient.confirmReturn(connection.getId(), returnSn);
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
}
