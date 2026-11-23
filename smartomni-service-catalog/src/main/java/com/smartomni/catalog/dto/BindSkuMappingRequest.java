package com.smartomni.catalog.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BindSkuMappingRequest {
    @NotNull(message = "skuId is required")
    private Long skuId;

    @NotNull(message = "platform is required")
    private String platform;

    private String platformItemId;
    private String externalSkuId;
    private String sellerSku;
    private BigDecimal platformPrice;
    private String productUrl;
}
