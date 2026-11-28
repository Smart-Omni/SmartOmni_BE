package com.smartomni.ai.dto.insights;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Deterministic sales & deductions comparison response for {@code GET /api/v1/ai/insights/sales}.
 */
public record SalesInsightsResponse(
        int periodDays,
        String periodLabel,
        String timezone,
        String currency,
        Instant generatedAt,
        BigDecimal totalSales,
        long totalOrders,
        BigDecimal totalRefunds,
        ChannelSalesInsights shopee,
        ChannelSalesInsights tiktok,
        List<TrendPoint> chart
) {
    public record ChannelSalesInsights(
            boolean connected,
            String shopName,
            BigDecimal sales,
            long orders,
            BigDecimal aov,
            BigDecimal refunds,
            BigDecimal cancelRate,
            BigDecimal trendPct,
            List<OrderBucket> orderSeries,
            DeductionsBreakdown deductions,
            List<TopProductItem> products,
            long unmappedSkuUnits,
            BigDecimal unmappedSkuSales
    ) {}

    public record OrderBucket(
            String label,
            long count
    ) {}

    public record TrendPoint(
            String label,
            BigDecimal shopeeSales,
            BigDecimal tiktokSales
    ) {}

    /**
     * Five canonical deduction buckets matching the unified FE "Khoản trừ" legend order:
     * 1. Phí sàn, 2. Vận chuyển shop chịu, 3. Tiền hoàn cho khách, 4. Phí ship hàng hoàn, 5. Phí khác / điều chỉnh.
     */
    public record DeductionsBreakdown(
            BigDecimal platformFees,
            BigDecimal sellerShippingCost,
            BigDecimal buyerRefunds,
            BigDecimal reverseShippingFee,
            BigDecimal otherAdjustments,
            BigDecimal totalDeductions,
            BigDecimal receivedEstimate,
            boolean estimated,
            long snapshotCoverageCount
    ) {}

    public record TopProductItem(
            Long skuId,
            String sku,
            String name,
            long units,
            BigDecimal sales,
            boolean mapped
    ) {}
}
