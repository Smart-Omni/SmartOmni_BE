package com.smartomni.ai.dto.insights;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Proposed, non-persistent response contract for the Insights mock.
 *
 * <p>This record is not wired to a controller, adapter, calculation service, or database entity. Monetary amounts
 * stay nullable: an absent marketplace value must not be serialized as a known zero. See
 * {@code docs/business-insights-preview-contract.md} for source mappings and implementation gaps.
 */
public record BusinessInsightsPreviewResponse(
        boolean demoData,
        String currency,
        AnalysisPeriod period,
        Instant generatedAt,
        List<ChannelInsights> channels
) {
    public enum Platform { SHOPEE, TIKTOK_SHOP }

    public enum DataState {
        COMPUTABLE,
        ESTIMATED,
        PARTIAL,
        ASSUMPTION_BASED,
        INSUFFICIENT_DATA,
        UNSUPPORTED
    }

    public enum MappingState { VERIFIED, UNMAPPED, AMBIGUOUS }

    public record AnalysisPeriod(
            Instant startInclusive,
            Instant endExclusive,
            String timeBasis,
            String timezone,
            String orderStatusPolicy
    ) {}

    public record ChannelInsights(
            Platform platform,
            Long connectionId,
            String shopExternalId,
            String shopName,
            DataState state,
            DataCoverage coverage,
            MetricValue merchandiseValue,
            MetricValue orderCount,
            MetricValue averageOrderValue,
            List<TopProduct> topProducts,
            FinanceBreakdown finance,
            ReturnCostSummary returnCosts
    ) {}

    public record DataCoverage(
            Instant sourceWatermark,
            String sourceEndpoint,
            int expectedDays,
            int coveredDays,
            int excludedOrderCount,
            List<String> missingInputs
    ) {}

    /** A metric carries its own availability, source, and formula semantics. */
    public record MetricValue(
            BigDecimal value,
            String unit,
            DataState state,
            String definition,
            String sourceEndpoint,
            String sourceField,
            List<String> missingInputs
    ) {}

    public record TopProduct(
            Long internalSkuId,
            String sellerSku,
            String productName,
            long units,
            BigDecimal merchandiseValue,
            MappingState mappingState,
            String sourceEndpoint
    ) {}

    public record FinanceBreakdown(
            DataState state,
            boolean estimated,
            String settlementStatus,
            MetricValue buyerPayment,
            MetricValue merchandiseValue,
            MetricValue platformFees,
            MetricValue sellerShippingCost,
            MetricValue buyerRefunds,
            MetricValue reverseShippingCost,
            MetricValue sellerSettlement,
            String sourceEndpoint,
            List<ChargeComponent> components,
            List<String> missingInputs
    ) {}

    /** Positive means money charged to the seller; negative means a seller credit. */
    public record ChargeComponent(
            String code,
            String label,
            BigDecimal sellerImpact,
            String currency,
            String transactionStatus,
            String sourceEndpoint,
            String sourceField,
            DataState state
    ) {}

    public record ReturnCostSummary(
            DataState state,
            int returnOrderCount,
            MetricValue buyerRefunds,
            MetricValue sellerReverseShipping,
            MetricValue retainedSellerFees,
            MetricValue knownSellerDebits,
            boolean profitImpactComputable,
            List<String> missingInputs
    ) {}
}
