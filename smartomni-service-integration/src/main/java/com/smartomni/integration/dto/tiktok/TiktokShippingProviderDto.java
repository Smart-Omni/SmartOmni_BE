package com.smartomni.integration.dto.tiktok;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO representing a TikTok Shop shipping provider & service option (D3).
 * Deserializes snake_case fields from TikTok Simulator and serializes camelCase fields to clients.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class TiktokShippingProviderDto {

    @JsonAlias({"shipping_provider_id"})
    private String shippingProviderId;

    @JsonAlias({"shipping_provider_name"})
    private String shippingProviderName;

    @JsonAlias({"shipping_service_id"})
    private String shippingServiceId;

    @JsonAlias({"shipping_service_name"})
    private String shippingServiceName;
}
