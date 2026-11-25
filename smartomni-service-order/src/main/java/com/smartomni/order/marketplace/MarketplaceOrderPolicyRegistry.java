package com.smartomni.order.marketplace;

import com.smartomni.order.entity.Order;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** One {@link MarketplaceOrderPolicy} per {@link Order.Platform}; a missing or duplicate policy fails at startup. */
@Component
public class MarketplaceOrderPolicyRegistry {

    private final Map<Order.Platform, MarketplaceOrderPolicy> policies;

    public MarketplaceOrderPolicyRegistry(List<MarketplaceOrderPolicy> policyList) {
        Map<Order.Platform, MarketplaceOrderPolicy> byPlatform = new EnumMap<>(Order.Platform.class);
        for (MarketplaceOrderPolicy policy : policyList) {
            MarketplaceOrderPolicy previous = byPlatform.put(policy.platform(), policy);
            if (previous != null) {
                throw new IllegalStateException("Two order policies for platform " + policy.platform() + ": "
                        + previous.getClass().getName() + ", " + policy.getClass().getName());
            }
        }
        for (Order.Platform platform : Order.Platform.values()) {
            if (!byPlatform.containsKey(platform)) {
                throw new IllegalStateException("No order policy for platform " + platform);
            }
        }
        this.policies = Collections.unmodifiableMap(byPlatform);
    }

    public MarketplaceOrderPolicy forPlatform(Order.Platform platform) {
        if (platform == null) {
            throw new IllegalArgumentException("platform must not be null");
        }
        return policies.get(platform);
    }
}
