package com.smartomni.ai.service;

import com.smartomni.ai.dto.insights.SalesInsightsResponse;
import com.smartomni.ai.dto.insights.SalesInsightsResponse.ChannelSalesInsights;
import com.smartomni.ai.dto.insights.SalesInsightsResponse.DeductionsBreakdown;
import com.smartomni.ai.dto.insights.SalesInsightsResponse.OrderBucket;
import com.smartomni.ai.dto.insights.SalesInsightsResponse.TopProductItem;
import com.smartomni.ai.dto.insights.SalesInsightsResponse.TrendPoint;
import com.smartomni.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Deterministic SQL aggregation for the "So sánh doanh thu" dashboard (GMT+7 / Asia/Ho_Chi_Minh).
 */
@Service
public class SalesInsightsService {

    static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("dd/MM");
    private static final DateTimeFormatter DAY_NUM_FMT = DateTimeFormatter.ofPattern("dd");
    private static final DateTimeFormatter LABEL_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    @Autowired
    public SalesInsightsService(NamedParameterJdbcTemplate jdbc) {
        this(jdbc, Clock.systemUTC());
    }

    SalesInsightsService(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public SalesInsightsResponse getSalesInsights(Long tenantId, int periodDays) {
        if (tenantId == null || tenantId <= 0) {
            throw new BusinessException("tenantId is required", HttpStatus.BAD_REQUEST, "TENANT_REQUIRED");
        }
        if (periodDays != 7 && periodDays != 30) {
            throw new BusinessException("periodDays must be 7 or 30", HttpStatus.BAD_REQUEST, "INVALID_PERIOD");
        }

        Instant now = clock.instant();
        LocalDate endDateInclusive = now.atZone(VN_ZONE).toLocalDate();
        LocalDate startDate = endDateInclusive.minusDays(periodDays - 1L);
        LocalDate prevStartDate = startDate.minusDays(periodDays);

        Instant startInstant = startDate.atStartOfDay(VN_ZONE).toInstant();
        Instant endExclusive = endDateInclusive.plusDays(1L).atStartOfDay(VN_ZONE).toInstant();
        Instant prevStartInstant = prevStartDate.atStartOfDay(VN_ZONE).toInstant();

        Map<String, String> connections = loadConnections(tenantId);
        List<BucketRange> buckets = buildBuckets(startDate, periodDays);

        ChannelSalesInsights shopee = buildChannel(tenantId, "shopee", connections, buckets, startInstant, endExclusive, prevStartInstant);
        ChannelSalesInsights tiktok = buildChannel(tenantId, "tiktok_shop", connections, buckets, startInstant, endExclusive, prevStartInstant);

        List<OrderRow> shopeeRows = loadOrders(tenantId, "shopee", startInstant, endExclusive);
        List<OrderRow> tiktokRows = loadOrders(tenantId, "tiktok_shop", startInstant, endExclusive);
        List<TrendPoint> chart = buildTrendChart(buckets, shopeeRows, tiktokRows);

        String periodLabel = startDate.format(LABEL_FMT) + " – " + endDateInclusive.format(LABEL_FMT);
        BigDecimal totalSales = shopee.sales().add(tiktok.sales());
        long totalOrders = shopee.orders() + tiktok.orders();
        BigDecimal totalRefunds = shopee.refunds().add(tiktok.refunds());

        return new SalesInsightsResponse(
                periodDays,
                periodLabel,
                VN_ZONE.getId(),
                "VND",
                now,
                totalSales,
                totalOrders,
                totalRefunds,
                shopee,
                tiktok,
                chart
        );
    }

    private ChannelSalesInsights buildChannel(Long tenantId, String platform, Map<String, String> connections,
                                              List<BucketRange> buckets, Instant start, Instant end, Instant prevStart) {
        boolean connected = connections.containsKey(platform);
        String shopName = connections.get(platform);

        List<OrderRow> currentOrders = loadOrders(tenantId, platform, start, end);
        List<OrderRow> prevOrders = loadOrders(tenantId, platform, prevStart, start);

        long eligibleCount = 0;
        long cancelledCount = 0;
        BigDecimal sales = BigDecimal.ZERO;
        for (OrderRow o : currentOrders) {
            if ("cancelled".equalsIgnoreCase(o.status())) {
                cancelledCount++;
            } else if (!"pending".equalsIgnoreCase(o.status())) {
                eligibleCount++;
                sales = sales.add(o.merchandiseValue());
            }
        }

        BigDecimal prevSales = BigDecimal.ZERO;
        for (OrderRow o : prevOrders) {
            if (!"cancelled".equalsIgnoreCase(o.status()) && !"pending".equalsIgnoreCase(o.status())) {
                prevSales = prevSales.add(o.merchandiseValue());
            }
        }

        BigDecimal aov = eligibleCount > 0
                ? sales.divide(BigDecimal.valueOf(eligibleCount), 0, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        long totalAttempted = eligibleCount + cancelledCount;
        BigDecimal cancelRate = totalAttempted > 0
                ? BigDecimal.valueOf(cancelledCount * 100.0 / totalAttempted).setScale(1, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal trendPct = prevSales.compareTo(BigDecimal.ZERO) > 0
                ? sales.subtract(prevSales).multiply(BigDecimal.valueOf(100))
                        .divide(prevSales, 1, RoundingMode.HALF_UP)
                : null;

        BigDecimal confirmedRefunds = loadConfirmedRefunds(tenantId, platform, start, end);
        List<OrderBucket> orderSeries = buildOrderBuckets(buckets, currentOrders);
        DeductionsBreakdown deductions = loadDeductions(tenantId, platform, start, end, sales, confirmedRefunds);

        List<TopProductItem> allProducts = loadTopProducts(tenantId, platform, start, end);
        List<TopProductItem> top10 = new ArrayList<>();
        long unmappedUnits = 0;
        BigDecimal unmappedSales = BigDecimal.ZERO;
        for (TopProductItem p : allProducts) {
            if (!p.mapped()) {
                unmappedUnits += p.units();
                unmappedSales = unmappedSales.add(p.sales());
            } else if (top10.size() < 10) {
                top10.add(p);
            }
        }

        return new ChannelSalesInsights(
                connected,
                shopName,
                sales,
                eligibleCount,
                aov,
                confirmedRefunds,
                cancelRate,
                trendPct,
                orderSeries,
                deductions,
                top10,
                unmappedUnits,
                unmappedSales
        );
    }

    private Map<String, String> loadConnections(Long tenantId) {
        String sql = """
                SELECT LOWER(platform::text) AS platform, COALESCE(shop_name, shop_id, platform::text) AS shop_name
                FROM marketplace_connections
                WHERE tenant_id = :tenantId AND status = 'connected'
                ORDER BY id ASC
                """;
        Map<String, String> map = new LinkedHashMap<>();
        jdbc.query(sql, new MapSqlParameterSource("tenantId", tenantId), rs -> {
            map.putIfAbsent(rs.getString("platform"), rs.getString("shop_name"));
        });
        return map;
    }

    private List<OrderRow> loadOrders(Long tenantId, String platform, Instant start, Instant end) {
        String sql = """
                SELECT LOWER(status::text) AS status,
                       COALESCE(merchandise_value,
                                GREATEST(0, COALESCE(original_product_price, total_amount, 0) - COALESCE(seller_discount, 0))) AS merch_val,
                       COALESCE(marketplace_created_at, created_at) AS order_ts
                FROM orders
                WHERE tenant_id = :tenantId
                  AND LOWER(platform::text) = :platform
                  AND COALESCE(marketplace_created_at, created_at) >= :start
                  AND COALESCE(marketplace_created_at, created_at) < :end
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("platform", platform)
                .addValue("start", Timestamp.from(start))
                .addValue("end", Timestamp.from(end));
        return jdbc.query(sql, params, (rs, rowNum) -> new OrderRow(
                rs.getString("status"),
                rs.getBigDecimal("merch_val") != null ? rs.getBigDecimal("merch_val") : BigDecimal.ZERO,
                rs.getTimestamp("order_ts").toInstant()
        ));
    }

    private BigDecimal loadConfirmedRefunds(Long tenantId, String platform, Instant start, Instant end) {
        String sql = """
                SELECT COALESCE(SUM(COALESCE(refund_amount, 0)), 0) AS total_refund
                FROM returns_refunds
                WHERE tenant_id = :tenantId
                  AND LOWER(platform::text) = :platform
                  AND (LOWER(status::text) IN ('approved', 'completed')
                       OR UPPER(COALESCE(marketplace_status, '')) IN ('ACCEPTED', 'RETURN_OR_REFUND_REQUEST_SUCCESS', 'RETURN_OR_REFUND_REQUEST_COMPLETE'))
                  AND COALESCE(marketplace_created_at, marketplace_updated_at, updated_at) >= :start
                  AND COALESCE(marketplace_created_at, marketplace_updated_at, updated_at) < :end
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("platform", platform)
                .addValue("start", Timestamp.from(start))
                .addValue("end", Timestamp.from(end));
        BigDecimal val = jdbc.queryForObject(sql, params, BigDecimal.class);
        return val != null ? val : BigDecimal.ZERO;
    }

    private DeductionsBreakdown loadDeductions(Long tenantId, String platform, Instant start, Instant end,
                                             BigDecimal sales, BigDecimal confirmedRefunds) {
        String sql = """
                SELECT COUNT(s.id) AS snap_count,
                       SUM(COALESCE(s.platform_fees, 0)) AS platform_fees,
                       SUM(COALESCE(s.seller_shipping_cost, 0)) AS seller_shipping_cost,
                       SUM(COALESCE(s.seller_return_refund, 0)) AS seller_return_refund,
                       SUM(COALESCE(s.reverse_shipping_fee, 0)) AS reverse_shipping_fee,
                       SUM(COALESCE(s.other_adjustments, 0)) AS other_adjustments,
                       BOOL_OR(s.estimated) AS any_estimated
                FROM order_finance_snapshots s
                JOIN orders o
                  ON o.tenant_id = s.tenant_id
                 AND LOWER(o.platform::text) = LOWER(s.platform::text)
                 AND o.platform_order_id = s.platform_order_id
                WHERE s.tenant_id = :tenantId
                  AND LOWER(s.platform::text) = :platform
                  AND LOWER(o.status::text) NOT IN ('cancelled', 'pending')
                  AND COALESCE(o.marketplace_created_at, o.created_at) >= :start
                  AND COALESCE(o.marketplace_created_at, o.created_at) < :end
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("platform", platform)
                .addValue("start", Timestamp.from(start))
                .addValue("end", Timestamp.from(end));

        return jdbc.queryForObject(sql, params, (rs, rowNum) -> {
            long snapCount = rs.getLong("snap_count");
            if (snapCount == 0) {
                BigDecimal refundsOnly = confirmedRefunds;
                BigDecimal estimate = sales.subtract(refundsOnly).max(BigDecimal.ZERO);
                return new DeductionsBreakdown(
                        null, null, refundsOnly, null, null,
                        refundsOnly, estimate, true, 0L
                );
            }
            BigDecimal platformFees = nz(rs.getBigDecimal("platform_fees"));
            BigDecimal shippingCost = nz(rs.getBigDecimal("seller_shipping_cost"));
            BigDecimal snapRefunds = nz(rs.getBigDecimal("seller_return_refund"));
            BigDecimal buyerRefunds = snapRefunds.compareTo(BigDecimal.ZERO) > 0 ? snapRefunds : confirmedRefunds;
            BigDecimal reverseShipping = nz(rs.getBigDecimal("reverse_shipping_fee"));
            BigDecimal otherAdj = nz(rs.getBigDecimal("other_adjustments"));
            BigDecimal totalDeductions = platformFees.add(shippingCost).add(buyerRefunds).add(reverseShipping).add(otherAdj);
            BigDecimal receivedEstimate = sales.subtract(totalDeductions).max(BigDecimal.ZERO);
            return new DeductionsBreakdown(
                    platformFees,
                    shippingCost,
                    buyerRefunds,
                    reverseShipping,
                    otherAdj,
                    totalDeductions,
                    receivedEstimate,
                    rs.getBoolean("any_estimated"),
                    snapCount
            );
        });
    }

    private List<TopProductItem> loadTopProducts(Long tenantId, String platform, Instant start, Instant end) {
        String sql = """
                SELECT oi.sku_id,
                       COALESCE(ps.sku_code, oi.seller_sku, oi.external_sku_id, 'UNMAPPED') AS sku_code,
                       COALESCE(NULLIF(TRIM(CONCAT_WS(' · ', p.name, ps.variant_name)), ''), oi.product_name, 'Sản phẩm chưa đối chiếu') AS display_name,
                       SUM(COALESCE(oi.quantity, 0)) AS units,
                       SUM(COALESCE(oi.merchandise_value, COALESCE(oi.unit_price, 0) * COALESCE(oi.quantity, 0))) AS sales
                FROM order_items oi
                JOIN orders o ON o.id = oi.order_id AND o.tenant_id = oi.tenant_id
                LEFT JOIN product_skus ps ON ps.id = oi.sku_id AND ps.tenant_id = oi.tenant_id
                LEFT JOIN products p ON p.id = ps.product_id AND p.tenant_id = oi.tenant_id
                WHERE oi.tenant_id = :tenantId
                  AND LOWER(o.platform::text) = :platform
                  AND LOWER(o.status::text) NOT IN ('cancelled', 'pending')
                  AND COALESCE(o.marketplace_created_at, o.created_at) >= :start
                  AND COALESCE(o.marketplace_created_at, o.created_at) < :end
                GROUP BY oi.sku_id, COALESCE(ps.sku_code, oi.seller_sku, oi.external_sku_id, 'UNMAPPED'),
                         COALESCE(NULLIF(TRIM(CONCAT_WS(' · ', p.name, ps.variant_name)), ''), oi.product_name, 'Sản phẩm chưa đối chiếu')
                ORDER BY sales DESC, units DESC
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("platform", platform)
                .addValue("start", Timestamp.from(start))
                .addValue("end", Timestamp.from(end));
        return jdbc.query(sql, params, (rs, rowNum) -> {
            long rawSkuId = rs.getLong("sku_id");
            boolean mapped = !rs.wasNull() && rawSkuId > 0;
            return new TopProductItem(
                    mapped ? rawSkuId : null,
                    rs.getString("sku_code"),
                    rs.getString("display_name"),
                    rs.getLong("units"),
                    nz(rs.getBigDecimal("sales")),
                    mapped
            );
        });
    }

    private static List<BucketRange> buildBuckets(LocalDate startDate, int periodDays) {
        List<BucketRange> buckets = new ArrayList<>();
        if (periodDays == 7) {
            for (int i = 0; i < 7; i++) {
                LocalDate day = startDate.plusDays(i);
                buckets.add(new BucketRange(
                        day.format(DAY_NUM_FMT),
                        day.atStartOfDay(VN_ZONE).toInstant(),
                        day.plusDays(1).atStartOfDay(VN_ZONE).toInstant()
                ));
            }
        } else {
            for (int i = 0; i < 6; i++) {
                LocalDate bStart = startDate.plusDays(i * 5L);
                LocalDate bEnd = bStart.plusDays(4);
                buckets.add(new BucketRange(
                        bStart.format(DAY_FMT) + "–" + bEnd.format(DAY_FMT),
                        bStart.atStartOfDay(VN_ZONE).toInstant(),
                        bEnd.plusDays(1).atStartOfDay(VN_ZONE).toInstant()
                ));
            }
        }
        return buckets;
    }

    private static List<OrderBucket> buildOrderBuckets(List<BucketRange> buckets, List<OrderRow> orders) {
        List<OrderBucket> out = new ArrayList<>(buckets.size());
        for (BucketRange b : buckets) {
            long count = orders.stream()
                    .filter(o -> !"cancelled".equalsIgnoreCase(o.status()) && !"pending".equalsIgnoreCase(o.status()))
                    .filter(o -> !o.createdAt().isBefore(b.start()) && o.createdAt().isBefore(b.end()))
                    .count();
            out.add(new OrderBucket(b.label(), count));
        }
        return out;
    }

    private static List<TrendPoint> buildTrendChart(List<BucketRange> buckets, List<OrderRow> shopeeRows, List<OrderRow> tiktokRows) {
        List<TrendPoint> out = new ArrayList<>(buckets.size());
        for (BucketRange b : buckets) {
            BigDecimal sSales = sumBucketSales(b, shopeeRows);
            BigDecimal tSales = sumBucketSales(b, tiktokRows);
            out.add(new TrendPoint(b.label(), sSales, tSales));
        }
        return out;
    }

    private static BigDecimal sumBucketSales(BucketRange b, List<OrderRow> rows) {
        return rows.stream()
                .filter(o -> !"cancelled".equalsIgnoreCase(o.status()) && !"pending".equalsIgnoreCase(o.status()))
                .filter(o -> !o.createdAt().isBefore(b.start()) && o.createdAt().isBefore(b.end()))
                .map(OrderRow::merchandiseValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private record OrderRow(String status, BigDecimal merchandiseValue, Instant createdAt) {}
    private record BucketRange(String label, Instant start, Instant end) {}
}
