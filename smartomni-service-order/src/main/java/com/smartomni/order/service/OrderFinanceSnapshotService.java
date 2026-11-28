package com.smartomni.order.service;

import com.smartomni.common.dto.OrderFinanceSnapshotRequest;
import com.smartomni.common.exception.BusinessException;
import com.smartomni.common.tenant.TenantContext;
import com.smartomni.order.entity.Order;
import com.smartomni.order.entity.OrderFinanceSnapshot;
import com.smartomni.order.repository.OrderFinanceSnapshotRepository;
import com.smartomni.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;

/**
 * Idempotent upsert of normalized marketplace financial snapshots (Shopee escrow & TikTok statement transactions).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderFinanceSnapshotService {

    private final OrderFinanceSnapshotRepository snapshotRepository;
    private final OrderRepository orderRepository;

    @Transactional
    public OrderFinanceSnapshot upsert(OrderFinanceSnapshotRequest req) {
        if (req == null || req.getPlatform() == null || req.getPlatformOrderId() == null || req.getPlatformOrderId().isBlank()) {
            throw new BusinessException("platform and platformOrderId are required", HttpStatus.BAD_REQUEST, "INVALID_FINANCE_SNAPSHOT");
        }
        Long tenantId = req.getTenantId() != null ? req.getTenantId() : TenantContext.getTenantId();
        if (tenantId == null || tenantId <= 0) {
            throw new BusinessException("tenantId is required", HttpStatus.BAD_REQUEST, "TENANT_REQUIRED");
        }
        Order.Platform platform = Order.Platform.valueOf(req.getPlatform().trim().toUpperCase(Locale.ROOT));
        String orderId = req.getPlatformOrderId().trim();

        OrderFinanceSnapshot snap = snapshotRepository
                .findByTenantIdAndPlatformAndPlatformOrderId(tenantId, platform, orderId)
                .orElseGet(() -> {
                    OrderFinanceSnapshot created = new OrderFinanceSnapshot();
                    created.setTenantId(tenantId);
                    created.setPlatform(platform);
                    created.setPlatformOrderId(orderId);
                    return created;
                });

        if (req.getConnectionId() != null) {
            snap.setConnectionId(req.getConnectionId());
        }
        if (snap.getOrderId() == null) {
            orderRepository.findByTenantIdAndPlatformAndPlatformOrderId(tenantId, platform, orderId)
                    .ifPresent(order -> snap.setOrderId(order.getId()));
        }
        if (req.getCurrency() != null && !req.getCurrency().isBlank()) {
            snap.setCurrency(req.getCurrency());
        }
        if (req.getSettlementStatus() != null && !req.getSettlementStatus().isBlank()) {
            snap.setSettlementStatus(req.getSettlementStatus());
        }
        snap.setEstimated(req.isEstimated());
        snap.setStatementId(req.getStatementId());
        snap.setStatementTime(req.getStatementTime());
        snap.setBuyerPayment(req.getBuyerPayment());
        snap.setMerchandiseValue(req.getMerchandiseValue());
        snap.setCommissionFee(req.getCommissionFee());
        snap.setServiceFee(req.getServiceFee());
        snap.setTransactionFee(req.getTransactionFee());
        snap.setTaxAmount(req.getTaxAmount());
        snap.setPlatformFees(req.getPlatformFees());
        snap.setActualShippingFee(req.getActualShippingFee());
        snap.setBuyerPaidShippingFee(req.getBuyerPaidShippingFee());
        snap.setPlatformShippingRebate(req.getPlatformShippingRebate());
        snap.setSellerShippingDiscount(req.getSellerShippingDiscount());
        snap.setSellerShippingCost(req.getSellerShippingCost());
        snap.setSellerReturnRefund(req.getSellerReturnRefund());
        snap.setReverseShippingFee(req.getReverseShippingFee());
        snap.setOtherAdjustments(req.getOtherAdjustments());
        snap.setSellerSettlement(req.getSellerSettlement());
        snap.setSourceEndpoint(req.getSourceEndpoint());
        snap.setSourceWatermark(req.getSourceWatermark() != null ? req.getSourceWatermark() : Instant.now());

        return snapshotRepository.save(snap);
    }
}
