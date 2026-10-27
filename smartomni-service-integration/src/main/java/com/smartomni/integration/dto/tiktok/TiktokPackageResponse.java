package com.smartomni.integration.dto.tiktok;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * DTO ánh xạ thông tin gói hàng vận chuyển (Package / Fulfillment) từ TikTok Shop.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class TiktokPackageResponse {

    @JsonProperty("package_id")
    private String packageId;

    @JsonProperty("tracking_number")
    private String trackingNumber;

    @JsonProperty("shipping_provider_name")
    private String shippingProviderName;

    @JsonProperty("shipping_provider_id")
    private String shippingProviderId;

    @JsonProperty("status")
    private String status;

    @JsonProperty("package_status")
    private String packageStatus;

    @JsonProperty("order_id_list")
    private List<String> orderIdList;

    @JsonProperty("shipping_type")
    private String shippingType;

    @JsonProperty("delivery_option_name")
    private String deliveryOptionName;

    @JsonProperty("create_time")
    private Long createTime;

    @JsonProperty("update_time")
    private Long updateTime;
}
