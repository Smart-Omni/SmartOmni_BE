package com.smartomni.integration.service.tiktok;

import com.smartomni.common.exception.BusinessException;
import com.smartomni.common.tenant.TenantContext;
import com.smartomni.integration.client.tiktok.TiktokVoucherClient;
import com.smartomni.integration.dto.tiktok.TiktokVoucherBatchDto;
import com.smartomni.integration.dto.tiktok.TiktokVoucherDto;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.repository.MarketplaceConnectionRepository;
import com.smartomni.integration.service.catalog.TiktokTenantConnections;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Lists the shop's Seller Center code batches so a loyalty tier can be linked to one, and holds the rules that
 * decide whether a code belongs to the batch a campaign is linked to (exact title, same percent).
 */
@Service
@RequiredArgsConstructor
public class TiktokVoucherBatchService {

    private final TiktokTenantConnections tenantConnections;
    private final MarketplaceConnectionRepository connections;
    private final TiktokVoucherClient voucherClient;
    private final TiktokVoucherStore store;
    private final TransactionTemplate transactionTemplate;

    /** Batches of the caller tenant's TikTok shop (Tenant Admin / Manager). */
    public List<TiktokVoucherBatchDto> listForCurrentTenant() {
        return list(tenantConnections.requireConnected());
    }

    /** Batches of one connection of the JWT tenant (internal calls from the order service). */
    public List<TiktokVoucherBatchDto> listForConnection(Long connectionId) {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null || connectionId == null) {
            throw new BusinessException("tenant and connectionId are required", HttpStatus.BAD_REQUEST, "INVALID_BATCH_REQUEST");
        }
        MarketplaceConnection connection = transactionTemplate.execute(status ->
                connections.findByIdAndTenantId(connectionId, tenantId).orElse(null));
        if (connection == null || connection.getPlatform() != MarketplaceConnection.Platform.TIKTOK_SHOP) {
            throw new BusinessException("TikTok Shop connection not found", HttpStatus.NOT_FOUND, "TIKTOK_CONNECTION_NOT_FOUND");
        }
        if (connection.getStatus() != MarketplaceConnection.ConnectionStatus.CONNECTED) {
            throw new BusinessException("TikTok Shop connection needs attention: " + connection.getStatus(),
                    HttpStatus.CONFLICT, "TIKTOK_CONNECTION_" + connection.getStatus().name());
        }
        return list(connection);
    }

    private List<TiktokVoucherBatchDto> list(MarketplaceConnection connection) {
        long tenantId = connection.getTenantId();
        List<TiktokVoucherDto> codes = voucherClient.searchCoupons(connection.getId(), null, List.of("ONGOING")).stream()
                .filter(c -> hasText(c.getTiktokVoucherId()) && hasText(c.getVoucherCode()) && hasText(c.getTitle()))
                .filter(c -> "ONGOING".equalsIgnoreCase(c.getStatus()))
                .toList();
        Set<String> free = transactionTemplate.execute(status -> {
            for (TiktokVoucherDto c : codes) {
                store.upsertPulled(tenantId, connection.getId(), connection.getShopId(), c);
            }
            return store.freeCouponIds(tenantId, connection.getId());
        });
        return group(codes, free == null ? Set.of() : free);
    }

    /** Groups codes by title and discount; sorted by title. */
    static List<TiktokVoucherBatchDto> group(List<TiktokVoucherDto> codes, Set<String> freeIds) {
        Map<List<Object>, TiktokVoucherBatchDto> batches = new LinkedHashMap<>();
        for (TiktokVoucherDto c : codes) {
            List<Object> key = List.of(c.getTitle().trim().toLowerCase(Locale.ROOT),
                    String.valueOf(c.getDiscountType()), plain(c.getDiscountValue()),
                    plain(c.getMaxDiscountAmount()), plain(c.getMinOrderAmount()));
            TiktokVoucherBatchDto batch = batches.computeIfAbsent(key, k -> TiktokVoucherBatchDto.builder()
                    .title(c.getTitle().trim())
                    .discountType(c.getDiscountType())
                    .discountValue(c.getDiscountValue())
                    .maxDiscountAmount(c.getMaxDiscountAmount())
                    .minOrderAmount(c.getMinOrderAmount())
                    .startTime(c.getStartTime())
                    .endTime(c.getEndTime())
                    .build());
            batch.setOngoingCodes(batch.getOngoingCodes() + 1);
            if (freeIds.contains(c.getTiktokVoucherId())) {
                batch.setFreeCodes(batch.getFreeCodes() + 1);
            }
            batch.setStartTime(earliest(batch.getStartTime(), c.getStartTime()));
            batch.setEndTime(earliest(batch.getEndTime(), c.getEndTime()));
        }
        List<TiktokVoucherBatchDto> list = new ArrayList<>(batches.values());
        list.sort(Comparator.comparing((TiktokVoucherBatchDto b) -> b.getTitle().toLowerCase(Locale.ROOT))
                .thenComparing(b -> plain(b.getDiscountValue())));
        return list;
    }

    /** The batch title a campaign is linked to: exact match, ignoring case and surrounding spaces. */
    public static boolean sameTitle(String a, String b) {
        return a != null && b != null && a.trim().equalsIgnoreCase(b.trim());
    }

    /** A PERCENT_OFF code giving exactly {@code percent} (10 and 10.00 are the same). */
    public static boolean samePercent(TiktokVoucherDto code, BigDecimal percent) {
        return percent != null && code.getDiscountValue() != null
                && "PERCENT_OFF".equalsIgnoreCase(code.getDiscountType())
                && code.getDiscountValue().compareTo(percent) == 0;
    }

    /** "30%" or "20000 VND off", for failure messages. */
    public static String describe(TiktokVoucherDto code) {
        String value = plain(code.getDiscountValue());
        return "PERCENT_OFF".equalsIgnoreCase(code.getDiscountType()) ? value + "%" : value + " VND off";
    }

    static String plain(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    private static Instant earliest(Instant a, Instant b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isBefore(b) ? a : b;
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
