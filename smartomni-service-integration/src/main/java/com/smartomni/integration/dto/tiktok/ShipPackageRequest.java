package com.smartomni.integration.dto.tiktok;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ShipPackageRequest {

    @JsonProperty("pickup_slot")
    private PickupSlot pickupSlot;

    @JsonProperty("self_shipment")
    private SelfShipment selfShipment;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PickupSlot {
        @JsonProperty("start_time")
        private Long startTime;

        @JsonProperty("end_time")
        private Long endTime;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SelfShipment {
        @JsonProperty("tracking_number")
        private String trackingNumber;

        @JsonProperty("shipping_provider_id")
        private String shippingProviderId;
    }
}
