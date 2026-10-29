package com.smartomni.integration.service.logistics;

import com.smartomni.integration.client.shopee.ShopeeLogisticsClient;
import com.smartomni.integration.client.shopee.ShopeeLogisticsClient.*;
import com.smartomni.integration.entity.MarketplaceConnection;
import lombok.Builder;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;

/**
 * Orchestrates logistics operations: shipping method selection, pickup dispatch,
 * tracking assignment, and Airway Bill (AWB) generation with Shopee Open API v2.
 */
@Slf4j
@Service
public class ShopeeLogisticsService {

    @Value
    @Builder
    public static class ShipOrderRequest {
        String shippingMode; // "pickup" or "dropoff"
        Long addressId;
        String pickupTimeId;
        Long branchId;
        String packageNumber;
    }

    @Value
    @Builder
    public static class ShipConfirmResult {
        String orderSn;
        String status;
        String trackingNumber;
        Long shipByDate;
        Long remainingSeconds;
    }

    private final ShopeeLogisticsClient logisticsClient;
    private final Clock clock;

    @Autowired
    public ShopeeLogisticsService(ShopeeLogisticsClient logisticsClient,
                                  @Qualifier("integrationBusinessClock") Clock clock) {
        this.logisticsClient = logisticsClient;
        this.clock = clock;
    }

    public ShopeeLogisticsService(ShopeeLogisticsClient logisticsClient) {
        this(logisticsClient, Clock.systemUTC());
    }

    public ShippingParameter getShippingParameter(MarketplaceConnection connection, String orderSn) {
        log.info("Fetching shipping parameters for Shopee order {} (tenant={})", orderSn, connection.getTenantId());
        return logisticsClient.getShippingParameter(connection.getId(), orderSn);
    }

    public ShipConfirmResult confirmShipment(MarketplaceConnection connection, String orderSn, ShipOrderRequest request) {
        log.info("Shipping order {} for tenant {}: mode={}", orderSn, connection.getTenantId(), request.getShippingMode());

        PickupParam pickup = null;
        DropoffParam dropoff = null;

        if ("pickup".equalsIgnoreCase(request.getShippingMode())) {
            pickup = new PickupParam(request.getAddressId(), request.getPickupTimeId());
        } else {
            dropoff = new DropoffParam(request.getBranchId() != null ? request.getBranchId() : 101L);
        }

        logisticsClient.shipOrder(connection.getId(), orderSn, request.getPackageNumber(), pickup, dropoff);

        String trackingNumber = logisticsClient.getTrackingNumber(connection.getId(), orderSn, request.getPackageNumber());

        // Automatically trigger AWB creation so thermal PDF is ready immediately
        try {
            logisticsClient.createShippingDocument(connection.getId(), orderSn, request.getPackageNumber());
        } catch (Exception ex) {
            log.warn("Could not immediately trigger AWB document for order {}: {}", orderSn, ex.getMessage());
        }

        Instant now = clock.instant();
        long defaultShipByDate = now.getEpochSecond() + 172800L; // 48 hours SLA window
        long remaining = defaultShipByDate - now.getEpochSecond();

        return ShipConfirmResult.builder()
                .orderSn(orderSn)
                .status("PROCESSED")
                .trackingNumber(trackingNumber)
                .shipByDate(defaultShipByDate)
                .remainingSeconds(Math.max(0, remaining))
                .build();
    }

    public byte[] downloadAwb(MarketplaceConnection connection, String orderSn) {
        log.info("Downloading thermal AWB PDF for Shopee order {} (tenant={})", orderSn, connection.getTenantId());

        String status = logisticsClient.getShippingDocumentStatus(connection.getId(), orderSn);
        if (!"READY".equalsIgnoreCase(status)) {
            logisticsClient.createShippingDocument(connection.getId(), orderSn, null);
        }

        return logisticsClient.downloadShippingDocument(connection.getId(), orderSn, null);
    }
}
