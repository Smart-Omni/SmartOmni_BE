package com.smartomni.common.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * DTO cập nhật thông tin vận đơn và tracking đơn hàng từ sự kiện Fulfillment / PACKAGE_STATUS_CHANGE.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class UpdateOrderTrackingRequest {

    @JsonProperty("tenant_id")
    private Long tenantId;

    @JsonProperty("platform")
    private String platform;

    @JsonProperty("platform_order_id")
    private String platformOrderId;

    @Builder.Default
    @JsonProperty("order_id_list")
    private List<String> orderIdList = new ArrayList<>();

    @JsonProperty("package_id")
    private String packageId;

    @JsonProperty("tracking_number")
    private String trackingNumber;

    @JsonProperty("shipping_provider")
    private String shippingProvider;

    @JsonProperty("package_status")
    private String packageStatus;

    @JsonProperty("courier_scanned_at")
    private Instant courierScannedAt;
}
