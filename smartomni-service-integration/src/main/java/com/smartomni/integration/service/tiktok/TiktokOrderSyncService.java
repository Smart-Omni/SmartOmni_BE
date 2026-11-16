package com.smartomni.integration.service.tiktok;

import com.smartomni.common.exception.BusinessException;
import com.smartomni.common.tenant.TenantContext;
import com.smartomni.integration.client.tiktok.TiktokOrderClient;
import com.smartomni.integration.dto.tiktok.OrderSearchRequest;
import com.smartomni.integration.dto.tiktok.OrderSearchResponse;
import com.smartomni.integration.dto.tiktok.TiktokOrderDetailResponse;
import com.smartomni.integration.dto.tiktok.TiktokOrderSyncResponse;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.service.catalog.CatalogMappingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Synchronizes TikTok Shop orders into SmartOmni's {@code orders} and {@code order_items} tables (D2).
 * Enforces PostgreSQL RLS context ({@code app.tenant_id} / {@code app.user_role}) and WF-02 loyalty dates:
 * when an order is {@code COMPLETED}, {@code completed_at} is persisted and {@code eligible_for_points_at}
 * is set to {@code completed_at + 15 days} without ever overwriting an existing {@code completed_at}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TiktokOrderSyncService {

    private static final int MAX_SYNC_PAGES = 20;
    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final long LOYALTY_ELIGIBILITY_DAYS = 15L;

    private final TiktokOrderClient orderClient;
    private final CatalogMappingService catalogMappingService;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public TiktokOrderSyncResponse syncOrders(MarketplaceConnection connection, Long fromDate, Long toDate) {
        if (connection == null || connection.getId() == null || connection.getTenantId() == null) {
            throw new BusinessException("Active TikTok connection is required", HttpStatus.BAD_REQUEST, "INVALID_CONNECTION");
        }

        List<TiktokOrderDetailResponse> fetchedOrders = fetchAllOrders(connection.getId(), fromDate, toDate);
        List<TiktokOrderDetailResponse> enrichedOrders = enrichOrders(connection.getId(), fetchedOrders);

        Long tenantId = connection.getTenantId();
        String role = TenantContext.getCurrentRole() != null
                ? TenantContext.getCurrentRole().toLowerCase(Locale.ROOT)
                : "tenant_admin";

        int[] counts = transactionTemplate.execute(status -> {
            // Ensure PostgreSQL RLS session variables are set on the active transaction connection
            jdbcTemplate.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            jdbcTemplate.queryForObject("SELECT set_config('app.user_role', ?, true)", String.class, role);

            int created = 0;
            int updated = 0;
            for (TiktokOrderDetailResponse order : enrichedOrders) {
                if (order == null || order.getOrderId() == null || order.getOrderId().isBlank()) {
                    continue;
                }
                boolean isNew = upsertOrder(tenantId, connection.getId(), order);
                if (isNew) {
                    created++;
                } else {
                    updated++;
                }
            }
            return new int[]{created, updated};
        });

        int createdCount = counts != null ? counts[0] : 0;
        int updatedCount = counts != null ? counts[1] : 0;

        log.info("TikTok order sync completed: tenantId={}, connectionId={}, totalFetched={}, created={}, updated={}",
                tenantId, connection.getId(), enrichedOrders.size(), createdCount, updatedCount);

        return TiktokOrderSyncResponse.builder()
                .totalFetched(enrichedOrders.size())
                .createdCount(createdCount)
                .updatedCount(updatedCount)
                .orders(enrichedOrders)
                .build();
    }

    private List<TiktokOrderDetailResponse> fetchAllOrders(Long connectionId, Long fromDate, Long toDate) {
        List<TiktokOrderDetailResponse> all = new ArrayList<>();
        Set<String> seenTokens = new HashSet<>();
        String pageToken = null;

        for (int page = 0; page < MAX_SYNC_PAGES; page++) {
            OrderSearchRequest request = OrderSearchRequest.builder()
                    .createTimeFrom(fromDate)
                    .createTimeTo(toDate)
                    .pageSize(DEFAULT_PAGE_SIZE)
                    .pageToken(pageToken)
                    .build();

            OrderSearchResponse response = orderClient.searchOrders(connectionId, request);
            if (response == null) {
                break;
            }
            if (response.getOrders() != null) {
                all.addAll(response.getOrders());
            }
            String nextToken = response.getNextPageToken();
            if (nextToken == null || nextToken.isBlank() || !seenTokens.add(nextToken)) {
                break;
            }
            pageToken = nextToken;
        }
        return all;
    }

    private List<TiktokOrderDetailResponse> enrichOrders(Long connectionId, List<TiktokOrderDetailResponse> orders) {
        List<TiktokOrderDetailResponse> result = new ArrayList<>(orders.size());
        for (TiktokOrderDetailResponse order : orders) {
            if (order == null || order.getOrderId() == null || order.getOrderId().isBlank()) {
                continue;
            }
            boolean missingItems = order.getLineItems() == null || order.getLineItems().isEmpty();
            boolean completedMissingTime = "COMPLETED".equalsIgnoreCase(order.getOrderStatus())
                    && order.getCompletedTime() == null;
            if (missingItems || completedMissingTime) {
                try {
                    TiktokOrderDetailResponse detail = orderClient.getOrderDetail(connectionId, order.getOrderId());
                    if (detail != null && detail.getOrderId() != null) {
                        result.add(detail);
                        continue;
                    }
                } catch (Exception ex) {
                    log.warn("Failed to fetch TikTok order detail for orderId={}: {}", order.getOrderId(), ex.getMessage());
                }
            }
            result.add(order);
        }
        return result;
    }

    private boolean upsertOrder(Long tenantId, Long connectionId, TiktokOrderDetailResponse order) {
        String dbStatus = mapStatus(order.getOrderStatus());
        String customerName = order.getRecipientAddress() != null ? order.getRecipientAddress().getName() : null;
        String customerPhone = order.getRecipientAddress() != null ? order.getRecipientAddress().getPhoneNumber() : null;
        String shippingAddress = resolveShippingAddress(order.getRecipientAddress());
        BigDecimal totalAmount = (order.getPayment() != null && order.getPayment().getTotalAmount() != null)
                ? order.getPayment().getTotalAmount()
                : BigDecimal.ZERO;
        String trackingNumber = order.getTrackingNumber();
        Instant createdAt = resolveTimestamp(order.getCreateTime(), Instant.now());

        Instant completedAt = null;
        Instant eligibleForPointsAt = null;
        if ("completed".equals(dbStatus)) {
            completedAt = resolveTimestamp(order.getCompletedTime(), Instant.now());
            eligibleForPointsAt = completedAt.plus(LOYALTY_ELIGIBILITY_DAYS, ChronoUnit.DAYS);
        }

        List<ExistingOrderRow> existing = jdbcTemplate.query(
                "SELECT id, completed_at, eligible_for_points_at FROM orders "
                        + "WHERE tenant_id = ? AND connection_id = ? AND platform_order_id = ?",
                (rs, i) -> new ExistingOrderRow(
                        rs.getLong("id"),
                        toInstant(rs.getTimestamp("completed_at")),
                        toInstant(rs.getTimestamp("eligible_for_points_at"))),
                tenantId, connectionId, order.getOrderId());

        long orderId;
        boolean isNew;
        if (existing.isEmpty()) {
            orderId = insertOrder(tenantId, connectionId, order.getOrderId(), dbStatus, trackingNumber,
                    customerName, customerPhone, shippingAddress, totalAmount,
                    completedAt, eligibleForPointsAt, createdAt);
            isNew = true;
        } else {
            ExistingOrderRow row = existing.get(0);
            orderId = row.id();
            // WF-02: never overwrite existing completed_at / eligible_for_points_at on repeated syncs
            Instant finalCompletedAt = row.completedAt() != null ? row.completedAt() : completedAt;
            Instant finalEligibleAt = row.completedAt() != null ? row.eligibleForPointsAt() : eligibleForPointsAt;

            jdbcTemplate.update(
                    "UPDATE orders SET status = ?::order_status, "
                            + "tracking_number = COALESCE(?, tracking_number), "
                            + "customer_name = COALESCE(?, customer_name), "
                            + "customer_phone = COALESCE(?, customer_phone), "
                            + "shipping_address = COALESCE(?, shipping_address), "
                            + "total_amount = ?, "
                            + "completed_at = ?, "
                            + "eligible_for_points_at = ?, "
                            + "updated_at = now() "
                            + "WHERE tenant_id = ? AND id = ?",
                    dbStatus, trackingNumber, customerName, customerPhone, shippingAddress,
                    totalAmount, toTimestamp(finalCompletedAt), toTimestamp(finalEligibleAt),
                    tenantId, orderId);
            isNew = false;
        }

        syncOrderItems(tenantId, orderId, order);
        return isNew;
    }

    private long insertOrder(Long tenantId, Long connectionId, String platformOrderId, String dbStatus,
                             String trackingNumber, String customerName, String customerPhone,
                             String shippingAddress, BigDecimal totalAmount,
                             Instant completedAt, Instant eligibleForPointsAt, Instant createdAt) {
        String sql = "INSERT INTO orders (tenant_id, connection_id, platform, platform_order_id, status, "
                + "tracking_number, customer_name, customer_phone, shipping_address, total_amount, source, "
                + "completed_at, eligible_for_points_at, created_at, updated_at) "
                + "VALUES (?, ?, 'tiktok_shop'::platform_type, ?, ?::order_status, ?, ?, ?, ?, ?, "
                + "'polling'::order_source, ?, ?, ?, now())";

        KeyHolder keys = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, new String[]{"id"});
            ps.setLong(1, tenantId);
            ps.setLong(2, connectionId);
            ps.setString(3, platformOrderId);
            ps.setString(4, dbStatus);
            ps.setString(5, trackingNumber);
            ps.setString(6, customerName);
            ps.setString(7, customerPhone);
            ps.setString(8, shippingAddress);
            ps.setBigDecimal(9, totalAmount);
            ps.setTimestamp(10, toTimestamp(completedAt));
            ps.setTimestamp(11, toTimestamp(eligibleForPointsAt));
            ps.setTimestamp(12, toTimestamp(createdAt));
            return ps;
        }, keys);

        Number key = keys.getKey();
        if (key == null) {
            throw new IllegalStateException("Insert into orders did not return generated id");
        }
        return key.longValue();
    }

    private void syncOrderItems(Long tenantId, long orderId, TiktokOrderDetailResponse order) {
        if (order.getLineItems() == null || order.getLineItems().isEmpty()) {
            return;
        }
        jdbcTemplate.update("DELETE FROM order_items WHERE tenant_id = ? AND order_id = ?", tenantId, orderId);

        for (TiktokOrderDetailResponse.LineItem item : order.getLineItems()) {
            Long internalSkuId = resolveInternalSkuId(tenantId, item.getSellerSku(), item.getSkuId());
            int quantity = item.getQuantity() != null && item.getQuantity() > 0 ? item.getQuantity() : 1;
            BigDecimal unitPrice = item.getSalePrice() != null
                    ? item.getSalePrice()
                    : (item.getOriginalPrice() != null ? item.getOriginalPrice() : BigDecimal.ZERO);
            String imageUrl = resolveImageUrl(tenantId, item);

            jdbcTemplate.update(
                    "INSERT INTO order_items (tenant_id, order_id, sku_id, quantity, unit_price, image_url, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, now(), now())",
                    tenantId, orderId, internalSkuId, quantity, unitPrice, imageUrl);
        }
    }

    private Long resolveInternalSkuId(Long tenantId, String sellerSku, String externalSkuId) {
        try {
            if (sellerSku != null && !sellerSku.isBlank()) {
                Long found = catalogMappingService.findInternalSkuId(tenantId, "tiktok_shop", sellerSku).orElse(null);
                if (found != null) {
                    return found;
                }
            }
            if (externalSkuId != null && !externalSkuId.isBlank()) {
                return catalogMappingService.findInternalSkuId(tenantId, "tiktok_shop", externalSkuId).orElse(null);
            }
        } catch (Exception ex) {
            log.warn("Could not resolve internal SKU mapping: tenantId={}, sellerSku={}, externalSkuId={}: {}",
                    tenantId, sellerSku, externalSkuId, ex.getMessage());
        }
        return null;
    }

    private String resolveImageUrl(Long tenantId, TiktokOrderDetailResponse.LineItem item) {
        if (item.getSkuImage() != null && !item.getSkuImage().isBlank()) {
            return item.getSkuImage();
        }
        try {
            if (item.getSellerSku() != null && !item.getSellerSku().isBlank()) {
                String found = catalogMappingService.findImageUrl(tenantId, "tiktok_shop", item.getSellerSku()).orElse(null);
                if (found != null) {
                    return found;
                }
            }
            if (item.getSkuId() != null && !item.getSkuId().isBlank()) {
                return catalogMappingService.findImageUrl(tenantId, "tiktok_shop", item.getSkuId()).orElse(null);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    static String mapStatus(String tiktokStatus) {
        if (tiktokStatus == null || tiktokStatus.isBlank()) {
            return "pending";
        }
        return switch (tiktokStatus.trim().toUpperCase(Locale.ROOT)) {
            case "UNPAID", "100" -> "pending";
            case "AWAITING_SHIPMENT", "111" -> "to_ship";
            case "AWAITING_COLLECTION", "112", "IN_TRANSIT", "121", "PARTIALLY_SHIPPING", "114" -> "shipped";
            case "DELIVERED", "122", "COMPLETED", "130" -> "completed";
            case "CANCELLED", "CANCELED", "140" -> "cancelled";
            case "IN_CANCEL", "ON_HOLD" -> "on_hold";
            case "RETURNED" -> "returned";
            default -> "pending";
        };
    }

    static Instant resolveTimestamp(Long rawTime, Instant fallback) {
        if (rawTime == null || rawTime <= 0) {
            return fallback;
        }
        return rawTime > 100_000_000_000L
                ? Instant.ofEpochMilli(rawTime)
                : Instant.ofEpochSecond(rawTime);
    }

    private static String resolveShippingAddress(TiktokOrderDetailResponse.RecipientAddress addr) {
        if (addr == null) {
            return null;
        }
        if (addr.getFullAddress() != null && !addr.getFullAddress().isBlank()) {
            return addr.getFullAddress();
        }
        String combined = Stream.of(
                        addr.getAddressLine1(),
                        addr.getAddressLine2(),
                        addr.getDistrict(),
                        addr.getCity(),
                        addr.getState(),
                        addr.getPostalCode(),
                        addr.getRegionCode())
                .filter(s -> s != null && !s.isBlank())
                .collect(Collectors.joining(", "));
        return combined.isBlank() ? null : combined;
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    private static Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private record ExistingOrderRow(long id, Instant completedAt, Instant eligibleForPointsAt) {
    }
}
