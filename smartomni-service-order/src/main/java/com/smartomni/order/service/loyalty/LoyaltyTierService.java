package com.smartomni.order.service.loyalty;

import com.smartomni.common.exception.BusinessException;
import com.smartomni.order.dto.loyalty.CreateCampaignRequest;
import com.smartomni.order.dto.loyalty.LoyaltyTierSetupDto;
import com.smartomni.order.dto.loyalty.SaveLoyaltyTiersRequest;
import com.smartomni.order.dto.loyalty.TiktokVoucherBatchDto;
import com.smartomni.order.dto.loyalty.UpdateCampaignRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Per-marketplace tier milestones (points needed + voucher %), stored as one reward campaign per tier.
 * Suggested defaults: Gold 500 / 10%, Platinum 1000 / 20%, Diamond 1500 / 30%.
 */
@Service
@RequiredArgsConstructor
public class LoyaltyTierService {

    static final int MAX_TIERS = 10;
    static final int DEFAULT_QUANTITY = 1000;
    static final Duration DEFAULT_DURATION = Duration.ofDays(365);
    public static final List<LoyaltyTierSetupDto.Tier> SUGGESTED = List.of(
            suggestion("Gold", 500, 10), suggestion("Platinum", 1000, 20), suggestion("Diamond", 1500, 30));

    static final String TIKTOK = "tiktok_shop";

    private final JdbcTemplate jdbcTemplate;
    private final LoyaltyCampaignService campaignService;
    private final IntegrationVoucherClient integrationVoucherClient;

    @Transactional(readOnly = true)
    public List<LoyaltyTierSetupDto> getSetup(Long tenantId) {
        requireTenant(tenantId);
        List<ConnectionRow> connections = jdbcTemplate.query(
                "SELECT id, platform::text AS platform, shop_name FROM marketplace_connections "
                        + "WHERE tenant_id = ? AND status = 'connected' AND platform::text IN ('shopee', 'tiktok_shop') "
                        + "ORDER BY platform, id",
                (rs, i) -> new ConnectionRow(rs.getLong("id"), rs.getString("platform"), rs.getString("shop_name")),
                tenantId);
        if (connections.isEmpty()) {
            return List.of();
        }
        Map<Long, List<LoyaltyTierSetupDto.Tier>> tiers = activeTiers(tenantId).stream()
                .collect(Collectors.groupingBy(TierRow::connectionId, Collectors.mapping(TierRow::tier, Collectors.toList())));
        return connections.stream().map(c -> LoyaltyTierSetupDto.builder()
                .connectionId(c.id())
                .platform(c.platform())
                .shopName(c.shopName())
                .provisioningMode(modeFor(c.platform()))
                .tiers(tiers.getOrDefault(c.id(), List.of()))
                .suggested(SUGGESTED)
                .build()).toList();
    }

    @Transactional
    public LoyaltyTierSetupDto saveTiers(Long staffUserId, Long tenantId, SaveLoyaltyTiersRequest request) {
        requireTenant(tenantId);
        if (request == null || request.getConnectionId() == null) {
            throw new BusinessException("connectionId is required", HttpStatus.BAD_REQUEST, "INVALID_TIER_REQUEST");
        }
        List<ConnectionRow> found = jdbcTemplate.query(
                "SELECT id, platform::text AS platform, shop_name FROM marketplace_connections "
                        + "WHERE tenant_id = ? AND id = ? AND status = 'connected' AND platform::text IN ('shopee', 'tiktok_shop')",
                (rs, i) -> new ConnectionRow(rs.getLong("id"), rs.getString("platform"), rs.getString("shop_name")),
                tenantId, request.getConnectionId());
        if (found.isEmpty()) {
            throw new BusinessException("A CONNECTED Shopee or TikTok shop is required",
                    HttpStatus.BAD_REQUEST, "CONNECTION_NOT_CONNECTED");
        }
        ConnectionRow connection = found.get(0);
        List<LoyaltyTierSetupDto.Tier> tiers = validate(request.getTiers(), connection.platform());
        String mode = modeFor(connection.platform());
        boolean tiktok = TIKTOK.equals(connection.platform());
        Instant now = Instant.now();
        Instant defaultEnd = now.plus(DEFAULT_DURATION);

        // TikTok: every tier hands out codes of the batch it is linked to, so the batch must exist now and give the
        // tier's percent. The campaign takes the batch's free codes as its quantity and ends when the batch ends.
        Map<LoyaltyTierSetupDto.Tier, BatchLink> links = new java.util.IdentityHashMap<>();
        if (tiktok) {
            List<TiktokVoucherBatchDto> batches = integrationVoucherClient.listTiktokBatches(tenantId, connection.id());
            for (LoyaltyTierSetupDto.Tier tier : tiers) {
                links.put(tier, linkBatch(tier, batches));
            }
        }

        Map<Long, Integer> reserved = jdbcTemplate.query(
                "SELECT id, reserved_count FROM loyalty_reward_campaigns WHERE tenant_id = ? AND connection_id = ? "
                        + "AND status = 'active'",
                (rs, i) -> Map.entry(rs.getLong("id"), rs.getInt("reserved_count")), tenantId, connection.id())
                .stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        Set<Long> existing = reserved.keySet();
        Set<Long> kept = new HashSet<>();
        for (LoyaltyTierSetupDto.Tier tier : tiers) {
            BatchLink link = links.get(tier);
            Instant endsAt = link == null ? defaultEnd : earliest(link.endTime(), defaultEnd);
            if (tier.getCampaignId() != null && existing.contains(tier.getCampaignId())) {
                UpdateCampaignRequest.UpdateCampaignRequestBuilder update = UpdateCampaignRequest.builder()
                        .name(tier.getName())
                        .requiredPoints(tier.getRequiredPoints())
                        .discountPercent(tier.getDiscountPercent())
                        .provisioningMode(mode)
                        .status("active");
                if (link != null) {
                    // codes already handed out by this campaign stay counted; the rest of the batch is what is left
                    update.tiktokBatchTitle(tier.getTiktokBatchTitle())
                            .quantity(reserved.get(tier.getCampaignId()) + link.freeCodes())
                            .endsAt(endsAt);
                }
                campaignService.updateCampaign(tenantId, tier.getCampaignId(), update.build());
                kept.add(tier.getCampaignId());
            } else {
                if (link != null && link.freeCodes() < 1) {
                    throw invalid("TikTok batch '" + tier.getTiktokBatchTitle() + "' has no free code left for "
                            + tier.getName());
                }
                campaignService.createCampaign(staffUserId, tenantId, CreateCampaignRequest.builder()
                        .connectionId(connection.id())
                        .name(tier.getName())
                        .requiredPoints(tier.getRequiredPoints())
                        .discountPercent(tier.getDiscountPercent())
                        .quantity(link == null ? DEFAULT_QUANTITY : link.freeCodes())
                        .provisioningMode(mode)
                        .tiktokBatchTitle(link == null ? null : tier.getTiktokBatchTitle())
                        .status("active")
                        .startsAt(now.minusSeconds(60))
                        .endsAt(endsAt)
                        .build());
            }
        }
        for (Long id : existing) {
            if (!kept.contains(id)) {
                jdbcTemplate.update("UPDATE loyalty_reward_campaigns SET status = 'closed', updated_at = now() "
                        + "WHERE tenant_id = ? AND id = ?", tenantId, id);
            }
        }
        return getSetup(tenantId).stream().filter(s -> s.getConnectionId().equals(connection.id())).findFirst()
                .orElseThrow();
    }

    static List<LoyaltyTierSetupDto.Tier> validate(List<LoyaltyTierSetupDto.Tier> tiers, String platform) {
        if (tiers == null || tiers.isEmpty() || tiers.size() > MAX_TIERS) {
            throw invalid("Configure between 1 and " + MAX_TIERS + " tiers");
        }
        Set<String> names = new HashSet<>();
        Set<Long> points = new HashSet<>();
        Set<String> batchTitles = new HashSet<>();
        List<LoyaltyTierSetupDto.Tier> clean = new ArrayList<>();
        for (LoyaltyTierSetupDto.Tier t : tiers) {
            String name = t == null || t.getName() == null ? "" : t.getName().trim();
            if (name.isEmpty() || name.length() > 60) {
                throw invalid("Each tier needs a name of 1-60 characters");
            }
            if (!names.add(name.toLowerCase(Locale.ROOT))) {
                throw invalid("Tier names must be unique: " + name);
            }
            if (t.getRequiredPoints() == null || t.getRequiredPoints() <= 0) {
                throw invalid("Points for " + name + " must be greater than 0");
            }
            if (!points.add(t.getRequiredPoints())) {
                throw invalid("Two tiers cannot need the same points (" + t.getRequiredPoints() + ")");
            }
            BigDecimal pct = t.getDiscountPercent();
            if (pct == null || pct.signum() <= 0 || pct.compareTo(BigDecimal.valueOf(100)) > 0) {
                throw invalid("Voucher % for " + name + " must be between 0 and 100");
            }
            if (pct.stripTrailingZeros().scale() > 0) {
                // Shopee vouchers and TikTok Seller Center code batches both take whole percentages only
                throw invalid(("shopee".equals(platform) ? "Shopee vouchers" : "TikTok code batches")
                        + " take a whole-number % (" + name + ")");
            }
            String batchTitle = null;
            if (TIKTOK.equals(platform)) {
                batchTitle = t.getTiktokBatchTitle() == null ? "" : t.getTiktokBatchTitle().trim();
                if (batchTitle.isEmpty() || batchTitle.length() > 100) {
                    throw invalid("Choose the TikTok code batch for " + name);
                }
                if (!batchTitles.add(batchTitle.toLowerCase(Locale.ROOT))) {
                    throw invalid("Two tiers cannot use the same TikTok code batch (" + batchTitle + ")");
                }
            }
            clean.add(LoyaltyTierSetupDto.Tier.builder().campaignId(t.getCampaignId()).name(name)
                    .requiredPoints(t.getRequiredPoints()).discountPercent(pct).tiktokBatchTitle(batchTitle).build());
        }
        clean.sort(java.util.Comparator.comparingLong(LoyaltyTierSetupDto.Tier::getRequiredPoints));
        return clean;
    }

    /** Free codes and end of the batch a TikTok tier is linked to: exact title, PERCENT_OFF, the tier's percent. */
    record BatchLink(int freeCodes, Instant endTime) { }

    static BatchLink linkBatch(LoyaltyTierSetupDto.Tier tier, List<TiktokVoucherBatchDto> batches) {
        String title = tier.getTiktokBatchTitle().trim();
        List<TiktokVoucherBatchDto> titled = batches.stream()
                .filter(b -> b.getTitle() != null && b.getTitle().trim().equalsIgnoreCase(title))
                .toList();
        if (titled.isEmpty()) {
            throw invalid("No ongoing TikTok code batch titled '" + title + "' for " + tier.getName());
        }
        List<TiktokVoucherBatchDto> matching = titled.stream()
                .filter(b -> "PERCENT_OFF".equalsIgnoreCase(b.getDiscountType()) && b.getDiscountValue() != null
                        && b.getDiscountValue().compareTo(tier.getDiscountPercent()) == 0)
                .toList();
        if (matching.isEmpty()) {
            TiktokVoucherBatchDto other = titled.get(0);
            String gives = "PERCENT_OFF".equalsIgnoreCase(other.getDiscountType())
                    ? plain(other.getDiscountValue()) + "%" : plain(other.getDiscountValue()) + " VND off";
            throw invalid("TikTok batch '" + title + "' gives " + gives + ", " + tier.getName() + " needs "
                    + plain(tier.getDiscountPercent()) + "%");
        }
        int free = matching.stream().mapToInt(TiktokVoucherBatchDto::getFreeCodes).sum();
        Instant end = matching.stream().map(TiktokVoucherBatchDto::getEndTime).filter(java.util.Objects::nonNull)
                .min(Instant::compareTo).orElse(null);
        return new BatchLink(free, end);
    }

    private static String plain(BigDecimal value) {
        return value == null ? "?" : value.stripTrailingZeros().toPlainString();
    }

    private static Instant earliest(Instant a, Instant b) {
        if (a == null) return b;
        return a.isBefore(b) ? a : b;
    }

    static String modeFor(String platform) {
        return "shopee".equals(platform) ? "api" : "import";
    }

    private List<TierRow> activeTiers(Long tenantId) {
        return jdbcTemplate.query(
                "SELECT id, connection_id, name, required_points, discount_percent, quantity, reserved_count, "
                        + "tiktok_batch_title, ends_at "
                        + "FROM loyalty_reward_campaigns WHERE tenant_id = ? AND status = 'active' "
                        + "ORDER BY connection_id, required_points, id",
                (rs, i) -> new TierRow(rs.getLong("connection_id"), LoyaltyTierSetupDto.Tier.builder()
                        .campaignId(rs.getLong("id"))
                        .name(rs.getString("name"))
                        .requiredPoints(rs.getLong("required_points"))
                        .discountPercent(rs.getBigDecimal("discount_percent"))
                        .quantity(rs.getInt("quantity"))
                        .availableQuantity(Math.max(0, rs.getInt("quantity") - rs.getInt("reserved_count")))
                        .tiktokBatchTitle(rs.getString("tiktok_batch_title"))
                        .endsAt(rs.getTimestamp("ends_at") == null ? null : rs.getTimestamp("ends_at").toInstant())
                        .build()),
                tenantId);
    }

    private static LoyaltyTierSetupDto.Tier suggestion(String name, long points, int percent) {
        return LoyaltyTierSetupDto.Tier.builder().name(name).requiredPoints(points)
                .discountPercent(BigDecimal.valueOf(percent)).build();
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(message, HttpStatus.BAD_REQUEST, "INVALID_TIERS");
    }

    private static void requireTenant(Long tenantId) {
        if (tenantId == null) {
            throw new BusinessException("Tenant context is required", HttpStatus.FORBIDDEN, "TENANT_MISSING");
        }
    }

    private record ConnectionRow(Long id, String platform, String shopName) {
    }

    private record TierRow(Long connectionId, LoyaltyTierSetupDto.Tier tier) {
    }
}
