package com.smartomni.order.marketplace;

import com.smartomni.order.entity.Order;
import com.smartomni.order.marketplace.shopee.ShopeeOrderPolicy;
import com.smartomni.order.marketplace.tiktok.TiktokOrderPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MarketplaceOrderPolicyRegistryTest {

    @Test
    void returnsThePolicyOfEachPlatform() {
        var registry = new MarketplaceOrderPolicyRegistry(List.of(new ShopeeOrderPolicy(null), new TiktokOrderPolicy(null)));

        assertInstanceOf(ShopeeOrderPolicy.class, registry.forPlatform(Order.Platform.SHOPEE));
        assertInstanceOf(TiktokOrderPolicy.class, registry.forPlatform(Order.Platform.TIKTOK_SHOP));
    }

    @Test
    void platformsDoNotShareMappings() {
        var registry = new MarketplaceOrderPolicyRegistry(List.of(new ShopeeOrderPolicy(null), new TiktokOrderPolicy(null)));

        // The same raw word means different things per platform; each table is applied only to its own platform.
        assertEquals(Order.OrderStatus.COMPLETED, registry.forPlatform(Order.Platform.TIKTOK_SHOP).mapStatus("COMPLETED"));
        assertEquals(Order.OrderStatus.COMPLETED, registry.forPlatform(Order.Platform.SHOPEE).mapStatus("TO_RETURN"));
        assertThrows(RuntimeException.class, () -> registry.forPlatform(Order.Platform.TIKTOK_SHOP).mapStatus("TO_RETURN"));
        assertThrows(RuntimeException.class, () -> registry.forPlatform(Order.Platform.SHOPEE).mapStatus("DELIVERED"));
        assertTrue(registry.forPlatform(Order.Platform.TIKTOK_SHOP).canTransition(Order.OrderStatus.PENDING, Order.OrderStatus.ON_HOLD));
        assertFalse(registry.forPlatform(Order.Platform.SHOPEE).canTransition(Order.OrderStatus.PENDING, Order.OrderStatus.ON_HOLD));
    }

    @Test
    void missingPolicyFailsAtStartup() {
        assertThrows(IllegalStateException.class,
                () -> new MarketplaceOrderPolicyRegistry(List.of(new ShopeeOrderPolicy(null))));
    }

    @Test
    void duplicatePolicyFailsAtStartup() {
        assertThrows(IllegalStateException.class, () -> new MarketplaceOrderPolicyRegistry(
                List.of(new ShopeeOrderPolicy(null), new ShopeeOrderPolicy(null), new TiktokOrderPolicy(null))));
    }
}
