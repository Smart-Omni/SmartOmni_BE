package com.smartomni.integration.service.tiktok;

import com.smartomni.integration.client.tiktok.TiktokFulfillmentClient;
import com.smartomni.integration.dto.tiktok.TiktokOrderDetailResponse;
import com.smartomni.integration.dto.tiktok.TiktokPackageResponse;
import com.smartomni.integration.entity.MarketplaceConnection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Adds the tracking number and carrier to a polled order. Order search and detail only name the packages; the tracking
 * number comes from Get Package Detail, which otherwise only the {@code PACKAGE_UPDATE} webhook reads. Without this a
 * lost webhook leaves the order without tracking even though polling brings its status up to date.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TiktokTrackingEnricher {

    /** Statuses in which shipment has been arranged, so a package can have a tracking number. */
    private static final Set<String> SHIPMENT_ARRANGED = Set.of(
            "PARTIALLY_SHIPPING", "AWAITING_COLLECTION", "IN_TRANSIT", "DELIVERED", "COMPLETED");

    private final TiktokFulfillmentClient fulfillmentClient;

    /**
     * Fills {@code trackingNumber} and {@code shippingProvider} of the order from the first package that has a tracking
     * number. Best effort: a failure is logged and never blocks the order, whose status matters more.
     */
    public void enrich(MarketplaceConnection connection, TiktokOrderDetailResponse order) {
        if (connection == null || order == null || hasText(order.getTrackingNumber()) || order.getOrderStatus() == null
                || !SHIPMENT_ARRANGED.contains(order.getOrderStatus().trim().toUpperCase(Locale.ROOT))) {
            return;
        }
        for (String packageId : packageIds(order)) {
            try {
                TiktokPackageResponse detail = hasText(connection.getShopCipher())
                        ? fulfillmentClient.getPackageDetailByShopCipher(connection.getShopCipher(), packageId)
                        : fulfillmentClient.getPackageDetail(connection.getId(), packageId);
                if (detail != null && hasText(detail.getTrackingNumber())) {
                    order.setTrackingNumber(detail.getTrackingNumber());
                    String carrier = hasText(detail.getShippingProviderName())
                            ? detail.getShippingProviderName() : detail.getShippingProviderId();
                    if (hasText(carrier)) {
                        order.setShippingProvider(carrier);
                    }
                    return;
                }
            } catch (Exception ex) {
                log.warn("Could not read tracking of TikTok package: connectionId={}, orderId={}, packageId={}: {}",
                        connection.getId(), order.getOrderId(), packageId, ex.getMessage());
            }
        }
    }

    private static Set<String> packageIds(TiktokOrderDetailResponse order) {
        Set<String> ids = new LinkedHashSet<>();
        Stream.concat(
                        Stream.of(order.getPackageId()),
                        Stream.concat(
                                order.getPackageList() == null ? Stream.<String>empty()
                                        : order.getPackageList().stream().map(TiktokOrderDetailResponse.OrderPackage::getPackageId),
                                order.getLineItems() == null ? Stream.<String>empty()
                                        : order.getLineItems().stream().map(TiktokOrderDetailResponse.LineItem::getPackageId)))
                .filter(TiktokTrackingEnricher::hasText)
                .forEach(ids::add);
        return ids;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
