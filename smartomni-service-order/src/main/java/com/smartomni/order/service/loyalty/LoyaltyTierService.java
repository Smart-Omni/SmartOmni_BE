package com.smartomni.order.service.loyalty;

import com.smartomni.common.exception.BusinessException;
import com.smartomni.order.dto.loyalty.CreateCampaignRequest;
import com.smartomni.order.dto.loyalty.LoyaltyTierSetupDto;
import com.smartomni.order.dto.loyalty.SaveLoyaltyTiersRequest;
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

    private final JdbcTemplate jdbcTemplate;
    private final LoyaltyCampaignService campaignService;

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

        Set<Long> existing = jdbcTemplate.query(
                "SELECT id FROM loyalty_reward_campaigns WHERE tenant_id = ? AND connection_id = ? "
                        + "AND status = 'active'",
                (rs, i) -> rs.getLong("id"), tenantId, connection.id()).stream().collect(Collectors.toSet());
        Set<Long> kept = new HashSet<>();
        Instant now = Instant.now();
        for (LoyaltyTierSetupDto.Tier tier : tiers) {
            if (tier.getCampaignId() != null && existing.contains(tier.getCampaignId())) {
                campaignService.updateCampaign(tenantId, tier.getCampaignId(), UpdateCampaignRequest.builder()
                        .name(tier.getName())
                        .requiredPoints(tier.getRequiredPoints())
                        .discountPercent(tier.getDiscountPercent())
                        .provisioningMode(mode)
                        .status("active")
                        .build());
                kept.add(tier.getCampaignId());
            } else {
                campaignService.createCampaign(staffUserId, tenantId, CreateCampaignRequest.builder()
                        .connectionId(connection.id())
                        .name(tier.getName())
                        .requiredPoints(tier.getRequiredPoints())
                        .discountPercent(tier.getDiscountPercent())
                        .quantity(DEFAULT_QUANTITY)
                        .provisioningMode(mode)
                        .status("active")
                        .startsAt(now.minusSeconds(60))
                        .endsAt(now.plus(DEFAULT_DURATION))
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
            if ("shopee".equals(platform) && pct.stripTrailingZeros().scale() > 0) {
                throw invalid("Shopee vouchers take a whole-number % (" + name + ")");
            }
            clean.add(LoyaltyTierSetupDto.Tier.builder().campaignId(t.getCampaignId()).name(name)
                    .requiredPoints(t.getRequiredPoints()).discountPercent(pct).build());
        }
        clean.sort(java.util.Comparator.comparingLong(LoyaltyTierSetupDto.Tier::getRequiredPoints));
        return clean;
    }

    static String modeFor(String platform) {
        return "shopee".equals(platform) ? "api" : "import";
    }

    private List<TierRow> activeTiers(Long tenantId) {
        return jdbcTemplate.query(
                "SELECT id, connection_id, name, required_points, discount_percent, quantity, reserved_count "
                        + "FROM loyalty_reward_campaigns WHERE tenant_id = ? AND status = 'active' "
                        + "ORDER BY connection_id, required_points, id",
                (rs, i) -> new TierRow(rs.getLong("connection_id"), LoyaltyTierSetupDto.Tier.builder()
                        .campaignId(rs.getLong("id"))
                        .name(rs.getString("name"))
                        .requiredPoints(rs.getLong("required_points"))
                        .discountPercent(rs.getBigDecimal("discount_percent"))
                        .quantity(rs.getInt("quantity"))
                        .availableQuantity(Math.max(0, rs.getInt("quantity") - rs.getInt("reserved_count")))
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
