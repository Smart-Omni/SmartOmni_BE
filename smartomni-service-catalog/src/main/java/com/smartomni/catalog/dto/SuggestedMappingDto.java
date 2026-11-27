package com.smartomni.catalog.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SuggestedMappingDto {
    private String platform;
    private String itemId;
    private String externalSkuId;
    private String sellerSku;
    private String name;
    private BigDecimal price;
    private Integer confidence;
    private String reason;
    private Long sourceSkuId;
    private String productUrl;
}
