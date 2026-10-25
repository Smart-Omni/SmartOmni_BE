package com.smartomni.integration.service.catalog;

import com.smartomni.integration.service.catalog.CatalogMappingStore.LinkRef;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;

/**
 * Applies one observed platform price to a link (FR-091..FR-093). Rules, all per SKU link:
 * <ul>
 *   <li>a missing, unparsable, negative or zero price is rejected and the stored price is left untouched;</li>
 *   <li>a currency different from the link's known currency is rejected (no silent conversion);</li>
 *   <li>an unchanged price only refreshes {@code last_synced_at};</li>
 *   <li>a change larger than {@code smartomni.price-sync.anomaly-threshold-percent} is recorded as an
 *       unapplied anomaly for a Manager to confirm and does not change the stored price (0 or less disables the check);</li>
 *   <li>any other change updates the link and writes a history row.</li>
 * </ul>
 * Must run inside a transaction with a tenant context.
 */
@Component
public class PriceChangePolicy {

    public enum Result { APPLIED, UNCHANGED, HELD_ANOMALY, INVALID_PRICE, CURRENCY_MISMATCH }

    private final CatalogMappingStore store;
    private final BigDecimal anomalyThresholdPercent;
    private final Clock clock;

    public PriceChangePolicy(CatalogMappingStore store,
                             @Value("${smartomni.price-sync.anomaly-threshold-percent:50}") BigDecimal anomalyThresholdPercent,
                             @org.springframework.beans.factory.annotation.Qualifier("integrationBusinessClock") Clock clock) {
        this.store = store;
        this.anomalyThresholdPercent = anomalyThresholdPercent;
        this.clock = clock;
    }

    public Result apply(LinkRef link, BigDecimal newPrice, String currency) {
        if (newPrice == null || newPrice.signum() <= 0) {
            return Result.INVALID_PRICE;
        }
        if (currency != null && link.currency() != null && !link.currency().equalsIgnoreCase(currency)) {
            return Result.CURRENCY_MISMATCH;
        }
        Instant now = clock.instant();
        BigDecimal old = link.platformPrice();
        if (old != null && old.compareTo(newPrice) == 0) {
            store.updateLinkPrice(link.id(), old, currency, now);
            return Result.UNCHANGED;
        }
        if (old != null && isAnomaly(old, newPrice)) {
            if (!store.hasPendingAnomaly(link.skuId(), newPrice)) {
                store.insertPriceHistory(link.skuId(), link.connectionId(), link.externalSkuId(), old, newPrice, currency, true, false);
            }
            store.touchLink(link.id(), now);
            return Result.HELD_ANOMALY;
        }
        store.updateLinkPrice(link.id(), newPrice, currency, now);
        store.insertPriceHistory(link.skuId(), link.connectionId(), link.externalSkuId(), old, newPrice, currency, false, true);
        return Result.APPLIED;
    }

    boolean isAnomaly(BigDecimal oldPrice, BigDecimal newPrice) {
        if (anomalyThresholdPercent == null || anomalyThresholdPercent.signum() <= 0 || oldPrice.signum() == 0) {
            return false;
        }
        BigDecimal changePercent = newPrice.subtract(oldPrice).abs()
                .multiply(BigDecimal.valueOf(100)).divide(oldPrice, 4, RoundingMode.HALF_UP);
        return changePercent.compareTo(anomalyThresholdPercent) > 0;
    }
}
